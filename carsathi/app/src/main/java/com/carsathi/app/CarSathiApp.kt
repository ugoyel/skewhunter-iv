package com.carsathi.app

import android.app.Application
import android.content.Context
import com.carsathi.app.data.SessionStore
import com.carsathi.app.data.db.AppDatabase
import com.carsathi.app.data.repo.AlertRepository
import com.carsathi.app.data.repo.AuthRepository
import com.carsathi.app.data.repo.CarRepository
import com.carsathi.app.data.repo.ChatRepository
import com.carsathi.app.data.repo.NotificationRepository
import com.carsathi.app.data.repo.RatingRepository
import com.carsathi.app.data.repo.RideRepository
import com.carsathi.app.data.repo.UserRepository
import com.carsathi.app.data.repo.WalletRepository
import com.carsathi.app.data.seed.DemoSeeder
import com.carsathi.app.data.seed.DemoSimulator
import com.carsathi.app.notifications.SystemNotifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Simple manual dependency container (swap the repositories for remote ones when a backend is added). */
class AppContainer(context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val db = AppDatabase.build(context)
    val session = SessionStore(context, scope)
    val notifier = SystemNotifier(context)
    val notifications = NotificationRepository(db, session, notifier)
    val auth = AuthRepository(db, session, notifications)
    val users = UserRepository(context, db)
    val cars = CarRepository(db)
    val chat = ChatRepository(db)
    val wallet = WalletRepository(db)
    val ratings = RatingRepository(db)
    val alerts = AlertRepository(db)
    val rides = RideRepository(db, notifications, chat, wallet)
    val simulator = DemoSimulator(scope, db, chat, notifications, rides, ratings)
    val seeder = DemoSeeder(db, session, rides, notifications, wallet)

    private val _seeded = MutableStateFlow(false)
    val seeded: StateFlow<Boolean> = _seeded

    fun start() {
        notifier.createChannel()
        scope.launch {
            try {
                seeder.ensureSeeded()
            } finally {
                _seeded.value = true
            }
            session.currentUserId()?.let { rides.settlePayouts(it) }
        }
    }
}

class CarSathiApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.start()
    }
}

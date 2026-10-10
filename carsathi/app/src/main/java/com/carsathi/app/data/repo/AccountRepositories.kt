package com.carsathi.app.data.repo

import android.content.Context
import android.net.Uri
import android.util.Base64
import com.carsathi.app.data.SessionStore
import com.carsathi.app.data.db.AppDatabase
import com.carsathi.app.data.db.CarEntity
import com.carsathi.app.data.db.RatingStat
import com.carsathi.app.data.db.UserEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import androidx.room.withTransaction

data class SignUpData(
    val email: String,
    val firstName: String,
    val lastName: String,
    val birthDate: Long,
    val gender: String,
    val password: String,
    val phone: String,
    val phoneVerified: Boolean,
    val newsletter: Boolean,
)

class AuthRepository(
    private val db: AppDatabase,
    private val session: SessionStore,
    private val notifications: NotificationRepository,
) {
    suspend fun emailExists(email: String): Boolean = db.users().byEmail(email.trim()) != null

    suspend fun signUp(data: SignUpData): Result<Long> = withContext(Dispatchers.IO) {
        if (emailExists(data.email)) return@withContext Result.failure(IllegalStateException("An account already exists with this email"))
        val salt = Passwords.newSalt()
        val id = db.users().insert(
            UserEntity(
                firstName = data.firstName.trim(),
                lastName = data.lastName.trim(),
                email = data.email.trim().lowercase(),
                phone = data.phone,
                phoneVerified = data.phoneVerified,
                passwordHash = Passwords.hash(data.password, salt),
                salt = salt,
                birthDate = data.birthDate,
                gender = data.gender,
                newsletter = data.newsletter,
            ),
        )
        session.setUser(id)
        notifications.notify(
            id, "WELCOME", "Welcome to carsathi, ${data.firstName.trim()}!",
            "Complete your profile, verify your ID and add your car to start sharing rides.",
        )
        Result.success(id)
    }

    suspend fun login(email: String, password: String): Result<UserEntity> = withContext(Dispatchers.IO) {
        val user = db.users().byEmail(email.trim())
            ?: return@withContext Result.failure(IllegalArgumentException("No account found with this email"))
        if (user.isDemo || user.passwordHash != Passwords.hash(password, user.salt)) {
            return@withContext Result.failure(IllegalArgumentException("Incorrect email or password"))
        }
        session.setUser(user.id)
        Result.success(user)
    }

    suspend fun logout() = session.setUser(null)

    suspend fun resetPassword(email: String, newPassword: String): Result<Unit> = withContext(Dispatchers.IO) {
        val user = db.users().byEmail(email.trim())
        if (user == null || user.isDemo) return@withContext Result.failure(IllegalArgumentException("No account found with this email"))
        val salt = Passwords.newSalt()
        db.users().update(user.copy(salt = salt, passwordHash = Passwords.hash(newPassword, salt)))
        Result.success(Unit)
    }

    suspend fun changePassword(userId: Long, current: String, newPassword: String): Result<Unit> = withContext(Dispatchers.IO) {
        val user = db.users().get(userId) ?: return@withContext Result.failure(IllegalStateException("Not signed in"))
        if (Passwords.hash(current, user.salt) != user.passwordHash) {
            return@withContext Result.failure(IllegalArgumentException("Your current password is incorrect"))
        }
        val salt = Passwords.newSalt()
        db.users().update(user.copy(salt = salt, passwordHash = Passwords.hash(newPassword, salt)))
        Result.success(Unit)
    }

    suspend fun deleteAccount(userId: Long) = withContext(Dispatchers.IO) {
        db.withTransaction {
            db.bookings().deleteByPassenger(userId)
            db.rides().deleteByDriver(userId)
            db.cars().deleteByOwner(userId)
            db.chat().deleteForUser(userId)
            db.ratings().deleteForUser(userId)
            db.notifications().deleteForUser(userId)
            db.alerts().deleteForUser(userId)
            db.wallet().deleteTransactions(userId)
            db.wallet().deleteMethods(userId)
            db.searches().clear(userId)
            db.users().delete(userId)
        }
        session.setUser(null)
    }
}

object Passwords {
    private val random = SecureRandom()

    fun newSalt(): String {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    fun hash(password: String, salt: String): String {
        val spec = PBEKeySpec(password.toCharArray(), salt.toByteArray(), 12_000, 256)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1")
        return Base64.encodeToString(factory.generateSecret(spec).encoded, Base64.NO_WRAP)
    }

    /** Returns an error message, or null when the password is acceptable. */
    fun validate(password: String): String? = when {
        password.length < 8 -> "Use at least 8 characters"
        password.none { it.isLetter() } -> "Add at least one letter"
        password.none { it.isDigit() } -> "Add at least one number"
        else -> null
    }
}

class UserRepository(private val context: Context, private val db: AppDatabase) {
    fun observe(id: Long): Flow<UserEntity?> = db.users().observe(id)

    suspend fun get(id: Long): UserEntity? = db.users().get(id)

    suspend fun update(user: UserEntity) = db.users().update(user)

    fun observeRatingStat(userId: Long): Flow<RatingStat?> = db.ratings().observeStat(userId)

    suspend fun ratingStat(userId: Long): RatingStat? = db.ratings().stats(listOf(userId)).firstOrNull()

    /** Rides driven + rides taken, including historical rides of seeded members. */
    suspend fun rideCount(user: UserEntity): Int =
        user.pastRides + db.rides().countByDriver(user.id) + db.bookings().confirmedCountForPassenger(user.id)

    /** Copies a picked photo into private storage and returns its path. */
    suspend fun savePhoto(userId: Long, uri: Uri, prefix: String = "avatar"): String? = withContext(Dispatchers.IO) {
        try {
            val dir = File(context.filesDir, "photos").apply { mkdirs() }
            val file = File(dir, "${prefix}_${userId}_${System.currentTimeMillis()}.jpg")
            context.contentResolver.openInputStream(uri)?.use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            } ?: return@withContext null
            file.absolutePath
        } catch (e: Exception) {
            null
        }
    }
}

class CarRepository(private val db: AppDatabase) {
    fun observe(ownerId: Long): Flow<List<CarEntity>> = db.cars().observeByOwner(ownerId)
    suspend fun list(ownerId: Long): List<CarEntity> = db.cars().byOwner(ownerId)
    suspend fun get(id: Long): CarEntity? = db.cars().get(id)
    suspend fun save(car: CarEntity): Long = if (car.id == 0L) db.cars().insert(car) else {
        db.cars().update(car); car.id
    }
    suspend fun delete(id: Long) = db.cars().delete(id)
}

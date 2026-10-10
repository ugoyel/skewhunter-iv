package com.carsathi.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        UserEntity::class, CarEntity::class, RideEntity::class, BookingEntity::class,
        ConversationEntity::class, MessageEntity::class, RatingEntity::class,
        NotificationEntity::class, AlertEntity::class, TransactionEntity::class,
        PaymentMethodEntity::class, SearchHistoryEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun users(): UserDao
    abstract fun cars(): CarDao
    abstract fun rides(): RideDao
    abstract fun bookings(): BookingDao
    abstract fun chat(): ChatDao
    abstract fun ratings(): RatingDao
    abstract fun notifications(): NotificationDao
    abstract fun alerts(): AlertDao
    abstract fun wallet(): WalletDao
    abstract fun searches(): SearchHistoryDao

    companion object {
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "carsathi.db")
                .fallbackToDestructiveMigration()
                .build()
    }
}

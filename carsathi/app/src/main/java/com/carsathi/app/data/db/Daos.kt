package com.carsathi.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface UserDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(user: UserEntity): Long

    @Insert
    suspend fun insertAll(users: List<UserEntity>): List<Long>

    @Update
    suspend fun update(user: UserEntity)

    @Query("SELECT * FROM users WHERE id = :id")
    suspend fun get(id: Long): UserEntity?

    @Query("SELECT * FROM users WHERE id = :id")
    fun observe(id: Long): Flow<UserEntity?>

    @Query("SELECT * FROM users WHERE id IN (:ids)")
    suspend fun getMany(ids: List<Long>): List<UserEntity>

    @Query("SELECT * FROM users WHERE LOWER(email) = LOWER(:email) LIMIT 1")
    suspend fun byEmail(email: String): UserEntity?

    @Query("SELECT * FROM users WHERE isDemo = 1")
    suspend fun demoUsers(): List<UserEntity>

    @Query("SELECT COUNT(*) FROM users")
    suspend fun count(): Int

    @Query("DELETE FROM users WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface CarDao {
    @Insert
    suspend fun insert(car: CarEntity): Long

    @Insert
    suspend fun insertAll(cars: List<CarEntity>): List<Long>

    @Update
    suspend fun update(car: CarEntity)

    @Query("DELETE FROM cars WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM cars WHERE id = :id")
    suspend fun get(id: Long): CarEntity?

    @Query("SELECT * FROM cars WHERE ownerId = :ownerId ORDER BY createdAt")
    fun observeByOwner(ownerId: Long): Flow<List<CarEntity>>

    @Query("SELECT * FROM cars WHERE ownerId = :ownerId ORDER BY createdAt")
    suspend fun byOwner(ownerId: Long): List<CarEntity>

    @Query("DELETE FROM cars WHERE ownerId = :ownerId")
    suspend fun deleteByOwner(ownerId: Long)
}

@Dao
interface RideDao {
    @Insert
    suspend fun insert(ride: RideEntity): Long

    @Insert
    suspend fun insertAll(rides: List<RideEntity>): List<Long>

    @Update
    suspend fun update(ride: RideEntity)

    @Query("SELECT * FROM rides WHERE id = :id")
    suspend fun get(id: Long): RideEntity?

    @Query("SELECT * FROM rides WHERE id = :id")
    fun observe(id: Long): Flow<RideEntity?>

    @Query("SELECT * FROM rides WHERE id IN (:ids)")
    suspend fun getMany(ids: List<Long>): List<RideEntity>

    @Query("SELECT * FROM rides WHERE status = 'ACTIVE' AND departureAt BETWEEN :start AND :end ORDER BY departureAt")
    suspend fun activeBetween(start: Long, end: Long): List<RideEntity>

    @Query("SELECT * FROM rides WHERE driverId = :driverId ORDER BY departureAt")
    fun observeByDriver(driverId: Long): Flow<List<RideEntity>>

    @Query("SELECT COUNT(*) FROM rides WHERE driverId = :driverId AND status = 'ACTIVE'")
    suspend fun countByDriver(driverId: Long): Int

    @Query("SELECT COUNT(*) FROM rides")
    suspend fun count(): Int

    @Query("DELETE FROM rides WHERE driverId = :driverId")
    suspend fun deleteByDriver(driverId: Long)

    @Query("DELETE FROM rides WHERE departureAt < :before AND driverId IN (SELECT id FROM users WHERE isDemo = 1) AND id NOT IN (SELECT rideId FROM bookings WHERE passengerId IN (SELECT id FROM users WHERE isDemo = 0))")
    suspend fun pruneOldDemoRides(before: Long)
}

@Dao
interface BookingDao {
    @Insert
    suspend fun insert(booking: BookingEntity): Long

    @Insert
    suspend fun insertAll(bookings: List<BookingEntity>)

    @Update
    suspend fun update(booking: BookingEntity)

    @Query("SELECT * FROM bookings WHERE id = :id")
    suspend fun get(id: Long): BookingEntity?

    @Query("SELECT * FROM bookings WHERE rideId = :rideId ORDER BY createdAt")
    fun observeByRide(rideId: Long): Flow<List<BookingEntity>>

    @Query("SELECT * FROM bookings WHERE rideId = :rideId ORDER BY createdAt")
    suspend fun byRide(rideId: Long): List<BookingEntity>

    @Query("SELECT * FROM bookings WHERE passengerId = :passengerId ORDER BY createdAt DESC")
    fun observeByPassenger(passengerId: Long): Flow<List<BookingEntity>>

    @Query("SELECT * FROM bookings WHERE passengerId = :passengerId ORDER BY createdAt DESC")
    suspend fun byPassenger(passengerId: Long): List<BookingEntity>

    @Query("SELECT rideId, SUM(seats) AS seats FROM bookings WHERE rideId IN (:rideIds) AND status IN ('PENDING','CONFIRMED') GROUP BY rideId")
    suspend fun seatsTaken(rideIds: List<Long>): List<RideSeats>

    @Query("SELECT COUNT(*) FROM bookings WHERE passengerId = :passengerId AND status = 'CONFIRMED'")
    suspend fun confirmedCountForPassenger(passengerId: Long): Int

    @Query("SELECT * FROM bookings WHERE rideId IN (SELECT id FROM rides WHERE driverId = :driverId)")
    fun observeForDriver(driverId: Long): Flow<List<BookingEntity>>

    @Query("SELECT * FROM bookings WHERE rideId IN (SELECT id FROM rides WHERE driverId = :driverId)")
    suspend fun forDriver(driverId: Long): List<BookingEntity>

    @Query("DELETE FROM bookings WHERE passengerId = :userId")
    suspend fun deleteByPassenger(userId: Long)
}

@Dao
interface ChatDao {
    @Insert
    suspend fun insertConversation(c: ConversationEntity): Long

    @Update
    suspend fun updateConversation(c: ConversationEntity)

    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun conversation(id: Long): ConversationEntity?

    @Query("SELECT * FROM conversations WHERE id = :id")
    fun observeConversation(id: Long): Flow<ConversationEntity?>

    @Query("SELECT * FROM conversations WHERE rideId = :rideId AND passengerId = :passengerId AND driverId = :driverId LIMIT 1")
    suspend fun find(rideId: Long, passengerId: Long, driverId: Long): ConversationEntity?

    @Query("SELECT * FROM conversations WHERE passengerId = :userId OR driverId = :userId ORDER BY lastAt DESC")
    fun observeForUser(userId: Long): Flow<List<ConversationEntity>>

    @Query("SELECT COALESCE(SUM(CASE WHEN passengerId = :userId THEN unreadPassenger ELSE unreadDriver END), 0) FROM conversations WHERE passengerId = :userId OR driverId = :userId")
    fun observeUnread(userId: Long): Flow<Int>

    @Insert
    suspend fun insertMessage(m: MessageEntity): Long

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY sentAt")
    fun observeMessages(conversationId: Long): Flow<List<MessageEntity>>

    @Query("DELETE FROM conversations WHERE passengerId = :userId OR driverId = :userId")
    suspend fun deleteForUser(userId: Long)
}

@Dao
interface RatingDao {
    @Insert
    suspend fun insert(r: RatingEntity): Long

    @Insert
    suspend fun insertAll(r: List<RatingEntity>)

    @Query("SELECT * FROM ratings WHERE toUserId = :userId ORDER BY createdAt DESC")
    fun observeReceived(userId: Long): Flow<List<RatingEntity>>

    @Query("SELECT * FROM ratings WHERE fromUserId = :userId ORDER BY createdAt DESC")
    fun observeGiven(userId: Long): Flow<List<RatingEntity>>

    @Query("SELECT toUserId AS userId, AVG(stars) AS avg, COUNT(*) AS cnt FROM ratings WHERE toUserId IN (:userIds) GROUP BY toUserId")
    suspend fun stats(userIds: List<Long>): List<RatingStat>

    @Query("SELECT toUserId AS userId, AVG(stars) AS avg, COUNT(*) AS cnt FROM ratings WHERE toUserId = :userId GROUP BY toUserId")
    fun observeStat(userId: Long): Flow<RatingStat?>

    @Query("SELECT COUNT(*) FROM ratings WHERE rideId = :rideId AND fromUserId = :fromUserId AND toUserId = :toUserId")
    suspend fun exists(rideId: Long, fromUserId: Long, toUserId: Long): Int

    @Query("DELETE FROM ratings WHERE fromUserId = :userId OR toUserId = :userId")
    suspend fun deleteForUser(userId: Long)
}

@Dao
interface NotificationDao {
    @Insert
    suspend fun insert(n: NotificationEntity): Long

    @Query("SELECT * FROM notifications WHERE userId = :userId ORDER BY createdAt DESC")
    fun observe(userId: Long): Flow<List<NotificationEntity>>

    @Query("SELECT COUNT(*) FROM notifications WHERE userId = :userId AND read = 0")
    fun observeUnread(userId: Long): Flow<Int>

    @Query("UPDATE notifications SET read = 1 WHERE userId = :userId")
    suspend fun markAllRead(userId: Long)

    @Query("UPDATE notifications SET read = 1 WHERE id = :id")
    suspend fun markRead(id: Long)

    @Query("DELETE FROM notifications WHERE userId = :userId")
    suspend fun deleteForUser(userId: Long)
}

@Dao
interface AlertDao {
    @Insert
    suspend fun insert(a: AlertEntity): Long

    @Query("DELETE FROM alerts WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM alerts WHERE userId = :userId ORDER BY createdAt DESC")
    fun observe(userId: Long): Flow<List<AlertEntity>>

    @Query("SELECT * FROM alerts")
    suspend fun all(): List<AlertEntity>

    @Query("DELETE FROM alerts WHERE userId = :userId")
    suspend fun deleteForUser(userId: Long)
}

@Dao
interface WalletDao {
    @Insert
    suspend fun insertTransaction(t: TransactionEntity): Long

    @Query("SELECT * FROM transactions WHERE userId = :userId ORDER BY createdAt DESC")
    fun observeTransactions(userId: Long): Flow<List<TransactionEntity>>

    @Insert
    suspend fun insertMethod(m: PaymentMethodEntity): Long

    @Query("DELETE FROM payment_methods WHERE id = :id")
    suspend fun deleteMethod(id: Long)

    @Query("SELECT * FROM payment_methods WHERE userId = :userId ORDER BY createdAt")
    fun observeMethods(userId: Long): Flow<List<PaymentMethodEntity>>

    @Query("SELECT COUNT(*) FROM transactions WHERE bookingId = :bookingId AND type = :type")
    suspend fun countFor(bookingId: Long, type: String): Int

    @Query("DELETE FROM transactions WHERE userId = :userId")
    suspend fun deleteTransactions(userId: Long)

    @Query("DELETE FROM payment_methods WHERE userId = :userId")
    suspend fun deleteMethods(userId: Long)
}

@Dao
interface SearchHistoryDao {
    @Insert
    suspend fun insert(s: SearchHistoryEntity): Long

    @Query("SELECT * FROM search_history WHERE userId = :userId ORDER BY searchedAt DESC LIMIT 30")
    fun observe(userId: Long): Flow<List<SearchHistoryEntity>>

    @Query("DELETE FROM search_history WHERE userId = :userId")
    suspend fun clear(userId: Long)
}

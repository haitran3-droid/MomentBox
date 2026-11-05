package com.ntq.training.momentbox.data.local.db

import android.app.Notification
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface NotificationDao {
    @Query("SELECT COUNT(*) FROM notifications WHERE is_read = 0 AND owner_id = :ownerId AND source = :source")
    fun unreadCountForScopeFlow(ownerId: String, source: String): Flow<Int>

    @Query("SELECT * FROM notifications WHERE owner_id = :ownerId AND source = :source ORDER BY created_at DESC")
    suspend fun getNotificationsForScope(ownerId: String, source: String): List<NotificationEntity>

    @Query("DELETE FROM notifications WHERE id = :id AND owner_id = :ownerId AND source = :source")
    suspend fun deleteForScope(id: Long, ownerId: String, source: String): Int
    @Query("Select count(*) from notifications where is_read = 0")
    fun unreadCountFlow(): Flow<Int>

    @Query("Select count(*) from notifications where is_read = 0")
    suspend fun getUnreadCount(): Int

    @Query("Select * from notifications order by created_at desc")
    fun getAllNotificationsFlow(): Flow<List<NotificationEntity>>

    @Query("Select * from notifications order by created_at desc")
    suspend fun getAllNotifications(): List<NotificationEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(notification: NotificationEntity): Long

    @Query("Update notifications set is_read = 1 where id = :id")
    suspend fun markAsRead(id: Long): Int

    @Query("Update notifications set is_read = 1 where is_read = 0")
    suspend fun markAllAsRead(): Int

    @Query("Delete from notifications where created_at < :cutoffMillis")
    suspend fun deleteOlderThan(cutoffMillis: Long): Int

    @Query("Delete from notifications")
    suspend fun deleteAll(): Int
}
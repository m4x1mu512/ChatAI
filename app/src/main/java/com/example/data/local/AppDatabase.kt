package com.example.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        ProviderEntity::class,
        AiModelEntity::class,
        ChatEntity::class,
        MessageEntity::class,
        AttachmentEntity::class,
    ],
    version = 3,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun providerDao(): ProviderDao
    abstract fun modelDao(): ModelDao
    abstract fun chatDao(): ChatDao
    abstract fun messageDao(): MessageDao
    abstract fun attachmentDao(): AttachmentDao
}

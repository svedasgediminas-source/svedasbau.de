package app.novaiptv.core.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        PlaylistEntity::class,
        ChannelEntity::class,
        ProgramEntity::class,
        VodCategoryEntity::class,
        MovieEntity::class,
        SeriesEntity::class,
        SeasonEntity::class,
        EpisodeEntity::class,
        FavoriteEntity::class,
        WatchHistoryEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class NovaDatabase : RoomDatabase() {
    abstract fun playlistDao(): PlaylistDao
    abstract fun channelDao(): ChannelDao
    abstract fun programDao(): ProgramDao
    abstract fun vodDao(): VodDao
    abstract fun favoriteDao(): FavoriteDao
    abstract fun historyDao(): HistoryDao

    companion object {
        const val NAME = "nova.db"

        fun create(context: Context): NovaDatabase =
            Room.databaseBuilder(context.applicationContext, NovaDatabase::class.java, NAME).build()
    }
}

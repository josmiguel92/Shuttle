package com.simplecity.amp_library.ui.screens.songs.menu

import android.content.Context
import android.util.Log
import com.annimon.stream.Stream
import com.annimon.stream.function.Predicate
import com.simplecity.amp_library.ShuttleApplication
import com.simplecity.amp_library.data.Repository
import com.simplecity.amp_library.data.Repository.AlbumArtistsRepository
import com.simplecity.amp_library.model.Playlist
import com.simplecity.amp_library.model.Song
import com.simplecity.amp_library.playback.MediaManager
import com.simplecity.amp_library.rx.UnsafeConsumer
import com.simplecity.amp_library.ui.common.Presenter
import com.simplecity.amp_library.ui.modelviews.SongView
import com.simplecity.amp_library.ui.screens.drawer.NavigationEventRelay
import com.simplecity.amp_library.ui.screens.drawer.NavigationEventRelay.NavigationEvent
import com.simplecity.amp_library.ui.screens.main.MainController
import com.simplecity.amp_library.ui.screens.playlist.detail.PlaylistDetailFragment
import com.simplecity.amp_library.ui.screens.songs.menu.SongMenuContract.View
import com.simplecity.amp_library.utils.LogUtils
import com.simplecity.amp_library.utils.RingtoneManager
import com.simplecity.amp_library.utils.playlists.PlaylistManager
import com.simplecityapps.recycler_adapter.model.ViewModel
import edu.usf.sas.pal.muser.model.UiEventType
import edu.usf.sas.pal.muser.util.EventUtils
import edu.usf.sas.pal.muser.util.FirebaseIOUtils
import io.reactivex.Observable
import io.reactivex.Single
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.schedulers.Schedulers
import javax.inject.Inject

open class SongMenuPresenter @Inject constructor(
    private val context: Context,
    private val mediaManager: MediaManager,
    private val playlistManager: PlaylistManager,
    private val blacklistRepository: Repository.BlacklistRepository,
    private val ringtoneManager: RingtoneManager,
    private val albumArtistsRepository: AlbumArtistsRepository,
    private val albumsRepository: Repository.AlbumsRepository,
    private val navigationEventRelay: NavigationEventRelay
) : Presenter<View>(), SongMenuContract.Presenter {

    override fun createPlaylist(songs: List<Song>) {
        view?.presentCreatePlaylistDialog(songs)
    }

    override fun addToPlaylist(context: Context, playlist: Playlist, songs: List<Song>) {
        if (playlist.type == Playlist.Type.FAVORITES) {
            songs.forEach {
                newUiEvent(it, UiEventType.FAVORITE)
            }
        }
        playlistManager.addToPlaylist(context, playlist, songs) { numSongs ->
            view?.onSongsAddedToPlaylist(playlist, numSongs)
        }
        songs.forEach {
            newUiEvent(it, UiEventType.ADD_TO_PLAYLIST)
        }
    }

    override fun addToQueue(songs: List<Song>) {
        songs.forEach {
            newUiEvent(it, UiEventType.ADD_TO_QUEUE)
        }
        mediaManager.addToQueue(songs) { numSongs ->
            view?.onSongsAddedToQueue(numSongs)
        }
    }

    override fun playNext(songs: List<Song>) {
        mediaManager.playNext(songs) { numSongs ->
            view?.onSongsAddedToQueue(numSongs)
        }
        songs.forEach {
            newUiEvent(it, UiEventType.PLAY_NEXT)
        }
    }

    override fun blacklist(songs: List<Song>) {
        blacklistRepository.addAllSongs(songs)
    }

    override fun delete(songs: List<Song>) {
        view?.presentDeleteDialog(songs)
    }

    override fun songInfo(song: Song) {
        view?.presentSongInfoDialog(song)
    }

    override fun setRingtone(song: Song) {
        if (RingtoneManager.requiresDialog(context)) {
            view?.presentRingtonePermissionDialog()
        } else {
            ringtoneManager.setRingtone(song) { view?.showRingtoneSetMessage() }
        }
    }

    override fun share(song: Song) {
        view?.shareSong(song)
    }

    override fun editTags(song: Song) {
        view?.presentTagEditorDialog(song)
    }

    override fun goToArtist(song: Song) {
        addDisposable(albumArtistsRepository.getAlbumArtists()
            .first(emptyList())
            .flatMapObservable { Observable.fromIterable(it) }
            .filter { albumArtist -> albumArtist.name == song.albumArtist.name && albumArtist.albums.containsAll(song.albumArtist.albums) }
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe(
                { albumArtist -> navigationEventRelay.sendEvent(NavigationEvent(NavigationEvent.Type.GO_TO_ARTIST, albumArtist, true)) },
                { error -> LogUtils.logException(TAG, "Failed to retrieve album artist", error) }
            ))
    }

    override fun goToAlbum(song: Song) {
        addDisposable(albumsRepository.getAlbums()
            .first(emptyList())
            .flatMapObservable { Observable.fromIterable(it) }
            .filter { album -> album.id == song.albumId }
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe(
                { album -> navigationEventRelay.sendEvent(NavigationEvent(NavigationEvent.Type.GO_TO_ALBUM, album, true)) },
                { error -> LogUtils.logException(TAG, "Failed to retrieve album", error) }
            ))
    }

    override fun goToGenre(song: Song) {
        addDisposable(song.getGenre(context)
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe(
                { genre -> navigationEventRelay.sendEvent(NavigationEvent(NavigationEvent.Type.GO_TO_GENRE, genre, true)) },
                { error -> LogUtils.logException(TAG, "Failed to retrieve genre", error) }
            ))
    }

    override fun removeSong(mainController: MainController, context: Context, song: Song) {

        val playlistDetailFragment = mainController
                .childFragmentManager
                .findFragmentByTag("PlaylistDetailFragment") as PlaylistDetailFragment?

        if (playlistDetailFragment != null) {
            val songView: ViewModel<*> = Stream.of<ViewModel<*>>(playlistDetailFragment.adapter.items)
                    .filter { value: ViewModel<*>? -> value is SongView && (value).song === song }
                    .findFirst().orElse(null)
            val index: Int = playlistDetailFragment.adapter.items.indexOf(songView)
            playlistDetailFragment.playlist.removeSong(song) { success: Boolean? ->
                if (!success!!) {
                    // Playlist removal failed, re-insert adapter item
                    playlistDetailFragment.adapter.addItem(index, songView)
                } else{
                    newUiEvent(song, UiEventType.REMOVE_FROM_PLAYLIST)
                }
            }
        }
    }

    override fun <T> transform(src: Single<List<T>>, dst: (List<T>) -> Unit) {
        addDisposable(
            src
                .observeOn(AndroidSchedulers.mainThread())
                .subscribeOn(Schedulers.io())
                .subscribe(
                    { items -> dst(items) },
                    { error -> LogUtils.logException(TAG, "Failed to transform src single", error) }
                )
        )
    }

    companion object {
        const val TAG = "SongMenuPresenter"
    }

    private fun newUiEvent(song: Song, uiEventType: UiEventType){
        val uiEvent = EventUtils.newUiEvent(song, uiEventType, ShuttleApplication.get())
        FirebaseIOUtils.saveUiEvent(uiEvent)
    }
}
package com.simplecity.amp_library.utils;

import android.annotation.SuppressLint;
import android.app.Dialog;
import android.app.ProgressDialog;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.MediaStore;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.annotation.WorkerThread;
import android.support.v4.util.Pair;
import android.text.Editable;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.style.StyleSpan;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.SubMenu;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import com.afollestad.materialdialogs.DialogAction;
import com.afollestad.materialdialogs.MaterialDialog;
import com.annimon.stream.Optional;
import com.annimon.stream.Stream;
import com.simplecity.amp_library.R;
import com.simplecity.amp_library.ShuttleApplication;
import com.simplecity.amp_library.interfaces.FileType;
import com.simplecity.amp_library.model.BaseFileObject;
import com.simplecity.amp_library.model.Playlist;
import com.simplecity.amp_library.model.Query;
import com.simplecity.amp_library.model.Song;
import com.simplecity.amp_library.playback.MediaManager;
import com.simplecity.amp_library.rx.UnsafeAction;
import com.simplecity.amp_library.rx.UnsafeConsumer;
import com.simplecity.amp_library.sql.SqlUtils;
import com.simplecity.amp_library.sql.sqlbrite.SqlBriteUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import io.reactivex.Completable;
import io.reactivex.Observable;
import io.reactivex.Single;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.schedulers.Schedulers;

/**
 * PlaylistUtils.java was derived from Shuttle's f-droid fork
 * https://github.com/quwepiro/Shuttle/blob/f-droid/app/src/main/java/com/simplecity/amp_library/utils/PlaylistUtils.java
 */

public class PlaylistUtils {

    private static final String TAG = "PlaylistUtils";

    public static final String ARG_PLAYLIST = "playlist";

    private PlaylistUtils() {

    }

    @WorkerThread
    public static String makePlaylistName(Context context) {

        String template = context.getString(R.string.new_playlist_name_template);
        int num = 1;

        Query query = new Query.Builder()
                .uri(MediaStore.Audio.Playlists.EXTERNAL_CONTENT_URI)
                .projection(new String[] { MediaStore.Audio.Playlists.NAME })
                .sort(MediaStore.Audio.Playlists.NAME)
                .build();

        Cursor cursor = SqlUtils.createQuery(context, query);

        if (cursor != null) {
            try {
                String suggestedName = String.format(template, num++);

                // Need to loop until we've made 1 full pass through without finding a match.
                // Looping more than once shouldn't happen very often, but will happen
                // if you have playlists named "New Playlist 1"/10/2/3/4/5/6/7/8/9, where
                // making only one pass would result in "New Playlist 10" being erroneously
                // picked for the new name.
                boolean done = false;
                while (!done) {
                    done = true;
                    cursor.moveToFirst();
                    while (!cursor.isAfterLast()) {
                        String playlistName = cursor.getString(0);
                        if (playlistName.compareToIgnoreCase(suggestedName) == 0) {
                            suggestedName = String.format(template, num++);
                            done = false;
                        }
                        cursor.moveToNext();
                    }
                }
                return suggestedName;
            } finally {
                cursor.close();
            }
        }
        return null;
    }

    public static Single<Integer> idForPlaylistObservable(Context context, String name) {
        Query query = new Query.Builder()
                .uri(MediaStore.Audio.Playlists.EXTERNAL_CONTENT_URI)
                .projection(new String[] { MediaStore.Audio.Playlists._ID })
                .selection(MediaStore.Audio.Playlists.NAME + "='" + name.replaceAll("'", "\''") + "'")
                .sort(MediaStore.Audio.Playlists.NAME)
                .build();

        return SqlBriteUtils.createSingle(context, cursor -> cursor.getInt(0), query, -1);
    }

    interface OnSavePlaylistListener {
        void onSave(Playlist playlist);
    }

    public static Completable createUpdatingPlaylistMenu(SubMenu subMenu) {
        return createPlaylistMenu(subMenu, true);
    }

    private static Completable createPlaylistMenu(SubMenu subMenu, boolean autoUpdate) {
        return DataManager.getInstance()
                .getPlaylistsRelay()
                .take(autoUpdate ? Long.MAX_VALUE : 1)
                .observeOn(AndroidSchedulers.mainThread())
                .doOnNext(playlists -> {
                    if (subMenu != null) {
                        subMenu.clear();
                        subMenu.add(0, MediaManager.Defs.NEW_PLAYLIST, 0, R.string.new_playlist);
                        for (Playlist playlist : playlists) {
                            final Intent intent = new Intent();
                            intent.putExtra(ARG_PLAYLIST, playlist);
                            subMenu.add(0, MediaManager.Defs.PLAYLIST_SELECTED, 0, playlist.name).setIntent(intent);
                        }
                    }
                })
                .ignoreElements()
                .doOnError(throwable -> LogUtils.logException(TAG, "createUpdatingPlaylistMenu failed", throwable))
                .subscribeOn(Schedulers.io());
    }

    /**
     * @return true if this item is a favorite
     */
    public static Observable<Boolean> isFavorite(@Nullable Song song) {
        if (song == null) {
            return Observable.just(false);
        }

        return DataManager.getInstance().getFavoriteSongsRelay()
                .map(songs -> songs.contains(song));
    }

    @SuppressLint("CheckResult")
    public static void addFileObjectsToPlaylist(Context context, Playlist playlist, List<BaseFileObject> fileObjects, UnsafeAction insertCallback) {

        ProgressDialog progressDialog = ProgressDialog.show(context, "", context.getString(R.string.gathering_songs), false);

        long folderCount = Stream.of(fileObjects)
                .filter(value -> value.fileType == FileType.FOLDER).count();

        if (folderCount > 0) {
            progressDialog.show();
        }

        ShuttleUtils.getSongsForFileObjects(fileObjects)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                        songs -> {
                            if (progressDialog != null && progressDialog.isShowing()) {
                                progressDialog.dismiss();
                            }
                            PermissionUtils.RequestStoragePermissions(() -> addToPlaylist(context, playlist, songs, insertCallback));
                        },
                        error -> LogUtils.logException(TAG, "Error getting songs for file object", error)
                );
    }

    /**
     * Method addToPlaylist.
     *
     * @param playlist Playlist
     * @param songs List<Song>
     * @return boolean true if the playlist addition was successful
     */
    @SuppressLint("CheckResult")
    public static void addToPlaylist(Context context, Playlist playlist, List<Song> songs, UnsafeAction insertCallback) {

        if (playlist == null || songs == null || songs.isEmpty()) {
            return;
        }

        final ArrayList<Song> mutableSongList = new ArrayList<>(songs);

        playlist.getSongsObservable().first(Collections.emptyList())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                        existingSongs -> {
                            if (!SettingsManager.getInstance().ignoreDuplicates()) {

                                List<Song> duplicates = Stream.of(existingSongs)
                                        .filter(mutableSongList::contains)
                                        .distinct()
                                        .toList();

                                if (!duplicates.isEmpty()) {

                                    @SuppressLint("InflateParams")
                                    View customView = LayoutInflater.from(context).inflate(R.layout.dialog_playlist_duplicates, null);
                                    TextView messageText = customView.findViewById(R.id.textView);
                                    CheckBox applyToAll = customView.findViewById(R.id.applyToAll);
                                    CheckBox alwaysAdd = customView.findViewById(R.id.alwaysAdd);

                                    if (duplicates.size() <= 1) {
                                        applyToAll.setVisibility(View.GONE);
                                        applyToAll.setChecked(false);
                                    }

                                    messageText.setText(getPlaylistRemoveString(context, duplicates.get(0)));
                                    applyToAll.setText(getApplyCheckboxString(context, duplicates.size()));

                                    DialogUtils.getBuilder(context)
                                            .title(R.string.dialog_title_playlist_duplicates)
                                            .customView(customView, false)
                                            .positiveText(R.string.dialog_button_playlist_duplicate_add)
                                            .autoDismiss(false)
                                            .onPositive((dialog, which) -> {
                                                //If we've only got one item, or we're applying it to all items
                                                if (duplicates.size() != 1 && !applyToAll.isChecked()) {
                                                    //If we're 'adding' this song, we remove it from the 'duplicates' list
                                                    duplicates.remove(0);
                                                    messageText.setText(getPlaylistRemoveString(context, duplicates.get(0)));
                                                    applyToAll.setText(getApplyCheckboxString(context, duplicates.size()));
                                                } else {
                                                    //Add all songs to the playlist
                                                    insertPlaylistItems(context, playlist, mutableSongList, existingSongs.size(), insertCallback);
                                                    SettingsManager.getInstance().setIgnoreDuplicates(alwaysAdd.isChecked());
                                                    dialog.dismiss();
                                                }
                                            })
                                            .negativeText(R.string.dialog_button_playlist_duplicate_skip)
                                            .onNegative((dialog, which) -> {
                                                //If we've only got one item, or we're applying it to all items
                                                if (duplicates.size() != 1 && !applyToAll.isChecked()) {
                                                    //If we're 'skipping' this song, we remove it from the 'duplicates' list,
                                                    // and from the ids to be added
                                                    mutableSongList.remove(duplicates.remove(0));
                                                    messageText.setText(getPlaylistRemoveString(context, duplicates.get(0)));
                                                    applyToAll.setText(getApplyCheckboxString(context, duplicates.size()));
                                                } else {
                                                    //Remove duplicates from our set of ids
                                                    Stream.of(duplicates)
                                                            .filter(mutableSongList::contains)
                                                            .forEach(mutableSongList::remove);
                                                    insertPlaylistItems(context, playlist, mutableSongList, existingSongs.size(), insertCallback);
                                                    SettingsManager.getInstance().setIgnoreDuplicates(alwaysAdd.isChecked());
                                                    dialog.dismiss();
                                                }
                                            })
                                            .show();
                                } else {
                                    insertPlaylistItems(context, playlist, mutableSongList, existingSongs.size(), insertCallback);
                                }
                            } else {
                                insertPlaylistItems(context, playlist, mutableSongList, existingSongs.size(), insertCallback);
                            }
                        },
                        error -> LogUtils.logException(TAG, "PlaylistUtils: Error determining existing songs", error)
                );
    }

    private static void insertPlaylistItems(@NonNull Context context,
                                            @NonNull Playlist playlist,
                                            @NonNull List<Song> songs, int songCount,
                                            UnsafeAction insertCallback) {

        if (songs.isEmpty()) {
            return;
        }

        ContentValues[] contentValues = new ContentValues[songs.size()];
        for (int i = 0, length = songs.size(); i < length; i++) {
            contentValues[i] = new ContentValues();
            contentValues[i].put(MediaStore.Audio.Playlists.Members.PLAY_ORDER, songCount + i);
            contentValues[i].put(MediaStore.Audio.Playlists.Members.AUDIO_ID, songs.get(i).id);
        }

        Uri uri = MediaStore.Audio.Playlists.Members.getContentUri("external", playlist.id);
        if (uri != null) {
            try {
                ShuttleApplication.getInstance().getContentResolver().bulkInsert(uri, contentValues);
                PlaylistUtils.showPlaylistToast(context, songs.size());
                if (insertCallback != null) {
                    insertCallback.run();
                }
            } catch (SecurityException e) {
                LogUtils.logException(TAG, "Failed to insert playlist items", e);
            }
        }
    }

    private static String getApplyCheckboxString(Context context, int count) {
        return String.format(context.getString(R.string.dialog_checkbox_playlist_duplicate_apply_all), count);
    }

    private static SpannableStringBuilder getPlaylistRemoveString(Context context, Song song) {
        SpannableStringBuilder spannableString = new SpannableStringBuilder(String.format(context.getString(R.string.dialog_message_playlist_add_duplicate), song.artistName, song.name));
        final StyleSpan boldSpan = new StyleSpan(android.graphics.Typeface.BOLD);
        spannableString.setSpan(boldSpan, 0, song.artistName.length() + song.name.length() + 3, Spannable.SPAN_INCLUSIVE_INCLUSIVE);
        return spannableString;
    }

    /**
     * Method clearPlaylist.
     *
     * @param playlistId int
     */
    public static void clearPlaylist(long playlistId) {
        final Uri uri = MediaStore.Audio.Playlists.Members.getContentUri("external", playlistId);
        ShuttleApplication.getInstance().getContentResolver().delete(uri, null, null);
    }

    @Nullable
    public static Playlist createPlaylist(Context context, String name) {

        Playlist playlist = null;
        long id = -1;

        if (!TextUtils.isEmpty(name)) {
            Query query = new Query.Builder()
                    .uri(MediaStore.Audio.Playlists.EXTERNAL_CONTENT_URI)
                    .projection(new String[] { MediaStore.Audio.PlaylistsColumns.NAME })
                    .selection(MediaStore.Audio.PlaylistsColumns.NAME + " = '" + name + "'")
                    .build();

            final Cursor cursor = SqlUtils.createQuery(context, query);

            if (cursor != null) {
                try {
                    int count = cursor.getCount();

                    if (count <= 0) {
                        final ContentValues values = new ContentValues(1);
                        values.put(MediaStore.Audio.PlaylistsColumns.NAME, name);
                        //Catch NPE occurring on Amazon devices.
                        try {
                            final Uri uri = context.getContentResolver().insert(
                                    MediaStore.Audio.Playlists.EXTERNAL_CONTENT_URI,
                                    values);
                            if (uri != null) {
                                id = Long.parseLong(uri.getLastPathSegment());
                            }
                        } catch (NullPointerException e) {
                            Log.e(TAG, "Failed to create playlist: " + e.getMessage());
                        }
                    }
                } finally {
                    cursor.close();
                }
            }
        }

        if (id != -1) {
            playlist = new Playlist(Playlist.Type.USER_CREATED, id, name, true, false, true, true, true);
        } else {
            Log.e(TAG, String.format("Failed to create playlist. Name: %s, id: %d", name, id));
        }

        return playlist;
    }

    @Nullable
    public static Optional<Playlist> createFavoritePlaylist() {
        Playlist playlist = PlaylistUtils.createPlaylist(ShuttleApplication.getInstance(), ShuttleApplication.getInstance().getString(R.string.fav_title));
        if (playlist != null) {
            playlist.canDelete = false;
            playlist.canRename = false;
            playlist.type = Playlist.Type.FAVORITES;
        }
        return Optional.ofNullable(playlist);
    }

    /**
     * Add a song to the favourites playlist
     */
    @SuppressLint("CheckResult")
    public static void addToFavorites(@NonNull Song song, UnsafeConsumer<Boolean> success) {
        Single.zip(
                Playlist.favoritesPlaylist()
                        .filter(Optional::isPresent)
                        .map(Optional::get)
                        .toSingle(),
                DataManager.getInstance().getFavoriteSongsRelay()
                        .first(Collections.emptyList())
                        .map(List::size),
                Pair::new)
                .map(pair -> {
                    Uri uri = MediaStore.Audio.Playlists.Members.getContentUri("external", pair.first.id);
                    ContentValues values = new ContentValues();
                    values.put(MediaStore.Audio.Playlists.Members.AUDIO_ID, song.id);
                    values.put(MediaStore.Audio.Playlists.Members.PLAY_ORDER, pair.second + 1);
                    Uri newUri = ShuttleApplication.getInstance().getContentResolver().insert(uri, values);
                    ShuttleApplication.getInstance().getContentResolver().notifyChange(MediaStore.Audio.Playlists.EXTERNAL_CONTENT_URI, null);
                    return newUri != null;
                })
                .delay(150, TimeUnit.MILLISECONDS)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                        success,
                        throwable -> LogUtils.logException(TAG, "Error adding to playlist", throwable)
                );
    }

    @SuppressLint("CheckResult")
    public static void removeFromFavorites(@NonNull Song song, @Nullable UnsafeConsumer<Boolean> success) {
        Playlist.favoritesPlaylist()
                .filter(Optional::isPresent)
                .map(Optional::get)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                        playlist -> removeFromPlaylist(playlist, song, success),
                        error -> LogUtils.logException(TAG, "PlaylistUtils: Error Removing from favorites", error)
                );
    }

    @SuppressLint("CheckResult")
    public static void removeFromPlaylist(@NonNull Playlist playlist, @NonNull Song song, @Nullable UnsafeConsumer<Boolean> success) {
        Single.fromCallable(() -> {
            int numTracksRemoved = 0;
            if (playlist.id >= 0) {
                final Uri uri = MediaStore.Audio.Playlists.Members.getContentUri("external", playlist.id);
                numTracksRemoved = ShuttleApplication.getInstance().getContentResolver().delete(uri, MediaStore.Audio.Playlists.Members.AUDIO_ID + "=" + song.id, null);
            }
            return numTracksRemoved;
        })
                .delay(150, TimeUnit.MILLISECONDS)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                        numTracksRemoved -> {
                            if (success != null) {
                                success.accept(numTracksRemoved > 0);
                            }
                        },
                        error -> LogUtils.logException(TAG, "PlaylistUtils: Error Removing from favorites", error)
                );
    }

    public static void showPlaylistToast(Context context, int numTracksAdded) {
        final String message = context.getResources().getQuantityString(R.plurals.NNNtrackstoplaylist, numTracksAdded, numTracksAdded);
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show();
    }

    @SuppressLint("CheckResult")
    private static void createPlaylistDialog(final Context context, final OnSavePlaylistListener listener) {

        @SuppressLint("InflateParams")
        View customView = LayoutInflater.from(context).inflate(R.layout.dialog_playlist, null);
        final EditText editText = customView.findViewById(R.id.editText);

        Observable.fromCallable(() -> makePlaylistName(context))
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                        name -> {
                            editText.setText(name);
                            if (!TextUtils.isEmpty(name)) {
                                editText.setSelection(name.length());
                            }
                        },
                        error -> LogUtils.logException(TAG, "PlaylistUtils: Error Setting playlist name", error)
                );

        MaterialDialog.Builder builder = DialogUtils.getBuilder(context)
                .customView(customView, false)
                .title(R.string.menu_playlist)
                .positiveText(R.string.create_playlist_create_text)
                .onPositive((materialDialog, dialogAction) -> {
                    String name = editText.getText().toString();
                    if (!name.isEmpty()) {
                        idForPlaylistObservable(context, name)
                                .subscribeOn(Schedulers.io())
                                .observeOn(AndroidSchedulers.mainThread())
                                .subscribe(
                                        id -> {
                                            Uri uri;
                                            if (id >= 0) {
                                                uri = ContentUris.withAppendedId(MediaStore.Audio.Playlists.EXTERNAL_CONTENT_URI, id);
                                                clearPlaylist(id);
                                            } else {
                                                ContentValues values = new ContentValues(1);
                                                values.put(MediaStore.Audio.Playlists.NAME, name);
                                                try {
                                                    uri = context.getContentResolver().insert(MediaStore.Audio.Playlists.EXTERNAL_CONTENT_URI, values);
                                                } catch (IllegalArgumentException | NullPointerException e) {
                                                    Toast.makeText(context, R.string.dialog_create_playlist_error, Toast.LENGTH_LONG).show();
                                                    uri = null;
                                                }
                                            }

                                            if (uri != null) {
                                                listener.onSave(new Playlist(Playlist.Type.USER_CREATED, Long.valueOf(uri.getLastPathSegment()), name, true, false, true, true, true));
                                            }
                                        },
                                        error -> LogUtils.logException(TAG, "PlaylistUtils: Error Saving playlist", error)
                                );
                    }
                })
                .negativeText(R.string.cancel);

        final Dialog dialog = builder.build();
        dialog.show();

        TextWatcher textWatcher = new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                // don't care about this one
            }

            //Fixme:
            // It's probably best to just query all playlist names first, and then check against
            //that list, rather than requerying for each char change.
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                String newText = editText.getText().toString();
                if (newText.trim().length() == 0) {
                    ((MaterialDialog) dialog).getActionButton(DialogAction.POSITIVE).setEnabled(false);
                } else {
                    ((MaterialDialog) dialog).getActionButton(DialogAction.POSITIVE).setEnabled(true);
                    // check if playlist with current name exists already, and warn the user if so.
                    idForPlaylistObservable(context, newText)
                            .subscribeOn(Schedulers.io())
                            .observeOn(AndroidSchedulers.mainThread())
                            .subscribe(
                                    id -> {
                                        if (id >= 0) {
                                            ((MaterialDialog) dialog).getActionButton(DialogAction.POSITIVE).setText(R.string.create_playlist_overwrite_text);
                                        } else {
                                            ((MaterialDialog) dialog).getActionButton(DialogAction.POSITIVE).setText(R.string.create_playlist_create_text);
                                        }
                                    },
                                    error -> LogUtils.logException(TAG, "PlaylistUtils: Error handling text change", error)
                            );
                }
            }

            public void afterTextChanged(Editable s) {
                // don't care about this one
            }
        };

        editText.addTextChangedListener(textWatcher);
    }


    public interface PlaylistIds {
        long RECENTLY_ADDED_PLAYLIST = -2;
        long MOST_PLAYED_PLAYLIST = -3;
        long PODCASTS_PLAYLIST = -4;
        long RECENTLY_PLAYED_PLAYLIST = -5;
    }
}

package edu.ucsd.cse110.sharednotes.model;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MediatorLiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.Observer;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class NoteRepository {
    private final NoteDao dao;
    private ScheduledFuture<?> clockFuture;
    private final NoteAPI api;

    private final MutableLiveData<Note> realLiveContent;

    private final MediatorLiveData<Note> liveContent;

    public NoteRepository(NoteDao dao) {
        this.dao = dao;
        this.api = new NoteAPI();
        realLiveContent = new MediatorLiveData<>();

        liveContent = new MediatorLiveData<>();
        liveContent.addSource(realLiveContent, liveContent::postValue);
    }

    // Synced Methods
    // ==============

    /**
     * This is where the magic happens. This method will return a LiveData object that will be
     * updated when the note is updated either locally or remotely on the server. Our activities
     * however will only need to observe this one LiveData object, and don't need to care where
     * it comes from!
     *
     * This method will always prefer the newest version of the note.
     *
     * @param title the title of the note
     * @return a LiveData object that will be updated when the note is updated locally or remotely.
     */
    public LiveData<Note> getSynced(String title) {
        var note = new MediatorLiveData<Note>();

        Observer<Note> updateFromRemote = theirNote -> {
            var ourNote = note.getValue();
            var our_updated_at = LocalDateTime.parse(ourNote.updatedAt).atZone(ZoneId.of("America/Los_Angeles")).toInstant().toEpochMilli();
            var their_updated_at = LocalDateTime.parse(theirNote.updatedAt).atZone(ZoneId.of("America/Los_Angeles")).toInstant().toEpochMilli();
            if (ourNote == null || our_updated_at < their_updated_at) {
                upsertLocal(theirNote);
            }
        };

        // If we get a local update, pass it on.
        note.addSource(getLocal(title), note::postValue);
        // If we get a remote update, update the local version (triggering the above observer)
        note.addSource(getRemote(title), updateFromRemote);

        return note;
    }

    public void upsertSynced(Note note) {
        upsertLocal(note);
        upsertRemote(note);
    }

    // Local Methods
    // =============

    public LiveData<Note> getLocal(String title) {
        return dao.get(title);
    }

    public LiveData<List<Note>> getAllLocal() {
        return dao.getAll();
    }

    public void upsertLocal(Note note) {
        note.updatedAt = LocalDateTime.now().toString();
        dao.upsert(note);
    }

    public void deleteLocal(Note note) {
        dao.delete(note);
    }

    public boolean existsLocal(String title) {
        return dao.exists(title);
    }

    // Remote Methods
    // ==============

    public LiveData<Note> getRemote(String title) {
        if (clockFuture != null) {
            clockFuture.cancel(true);
        }
        if (note != null) {
            upsertSynced(note);
        }
        var executor = Executors.newSingleThreadScheduledExecutor();
        ScheduledFuture<?> clockFuture = executor.scheduleAtFixedRate(() -> {
            Note temp = api.get(title);
            if (temp != null) {
                realLiveContent.postValue(temp);
            }
        }, 0, 3, TimeUnit.SECONDS);


        // Start by fetching the note from the server ONCE.
        // Then, set up a background thread that will poll the server every 3 seconds.
        // You may (but don't have to) want to cache the LiveData's for each title, so that
        // you don't create a new polling thread every time you call getRemote with the same title.
        return liveContent;
    }

    public void upsertRemote(Note note) {
        api.put(note.title, note.content);
    }
}

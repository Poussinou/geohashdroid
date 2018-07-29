/**
 * QueueService.java
 * Copyright (C)2018 Nicholas Killewald
 * 
 * This file is distributed under the terms of the BSD license.
 * The source package should have a LICENCE file at the toplevel.
 */
package net.exclaimindustries.tools;

import android.app.Service;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.database.SQLException;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.Log;

import java.util.Calendar;
import java.util.LinkedList;
import java.util.List;

/**
 * <p>
 * A <code>QueueService</code> is similar in theory to an {@link android.app.IntentService},
 * with the exception that the <code>Intent</code> is stored in a queue and
 * dealt with that way.  This also means the queue can be observed and iterated
 * as need be to, for instance, get a list of currently-waiting things to
 * process.
 * </p>
 * 
 * <p>
 * Note that while <code>QueueService</code> has many superficial similarities
 * to <code>IntentService</code>, it is NOT a subclass of it.  They just don't
 * work similarly enough under the hood to justify it.
 * </p>
 * 
 * @author Nicholas Killewald
 */
public abstract class QueueService extends Service {
    private static final String DEBUG_TAG = "QueueService";

    private volatile Looper mServiceLooper;
    private volatile ServiceHandler mServiceHandler;

    private DatabaseHelper mHelper;
    private SQLiteDatabase mDatabase;

    private final class ServiceHandler extends Handler {
        public ServiceHandler(Looper looper) {
            // WE'RE IN A THREAD NOW!
            super(looper);
        }

        public void handleMessage(Message msg) {
            // Quick!  Hand this off to handleCommand!  It might start ANOTHER
            // thread to deal with this.
            handleCommand((Intent)msg.obj);
        }
    }
    
    /**
     * Codes returned from onHandleIntent that tells the queue what to do next.
     */
    protected enum ReturnCode {
        /** Queue should continue as normal. */
        CONTINUE,
        /**
         * Queue should pause until resumed later.  Useful for temporary
         * errors.  The queue will not be emptied, and the Intent which caused
         * this pause won't be removed (though see {@link #COMMAND_RESUME_SKIP_FIRST}).
         */
        PAUSE,
        /**
         * Queue should stop entirely and not be resumed.  This implies the
         * queue will be emptied.
         */
        STOP
    }

    /** The name of the table storing everything. */
    private static final String TABLE_QUEUE = "queue";

    /** Everybody needs a rowid, right? */
    private static final String KEY_QUEUE_ROWID = "_id";
    /** The timestamp of the data.  We sort by this. */
    private static final String KEY_QUEUE_TIMESTAMP = "timestamp";
    /** The serialized data itself.  Treat as an opaque string. */
    private static final String KEY_QUEUE_DATA = "data";

    /**
     * The database gets by with a little help from this.
     */
    private class DatabaseHelper extends SQLiteOpenHelper {

        private static final int DATABASE_VERSION = 1;

        private static final String CREATE_QUEUE_TABLE =
                "CREATE TABLE " + TABLE_QUEUE
                    + " (" + KEY_QUEUE_ROWID + " INTEGER PRIMARY KEY AUTOINCREMENT, "
                    + KEY_QUEUE_TIMESTAMP + " INTEGER NOT NULL, "
                    + KEY_QUEUE_DATA + " TEXT NOT NULL);";

        DatabaseHelper(Context context) {
            super(context, getQueueDatabaseName(), null, DATABASE_VERSION);
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            db.execSQL(CREATE_QUEUE_TABLE);
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            // This is version 1, there's no upgrading right now.
        }
    }
    
    /**
     * Send an Intent with this extra data in it, set to one of the command
     * statics, to send a command.
     */
    public static final String COMMAND_EXTRA = "net.exclaimindustries.tools.QUEUETHREAD_COMMAND";
    
    /**
     * Command code sent to ask a paused QueueService to resume processing.
     */
    public static final int COMMAND_RESUME = 0;
    /**
     * Command code sent to ask a paused QueueService to resume processing,
     * skipping the first thing in the queue.
     */
    public static final int COMMAND_RESUME_SKIP_FIRST = 1;
    /**
     * Command code sent to ask a paused QueueService to give up entirely and
     * empty the queue (and by extension stop the service).  Note that this is
     * NOT guaranteed to stop the queue if it is currently not paused.
     */
    public static final int COMMAND_ABORT = 2;
    
    private Thread mThread;
    
    // Whether or not the queue is currently paused.
    private volatile boolean mIsPaused;
    
    public QueueService() {
        super();
        
        // We're not paused by default.
        mIsPaused = false;
    }
    
    @Override
    public void onCreate() {
        super.onCreate();

        // The database should have all the data we need already, and we'll be
        // reading directly from it as we go.  What we DO need right off the bat
        // is whether or not anything's in there, because if there is, we're
        // going to assume we were paused in a previous life.
        if(getQueueCount() > 0)
            mIsPaused = true;
        
        // (Re)start the HandlerThread.  We'll wait for further instructions.
        HandlerThread thread = new HandlerThread("QueueService Handler");
        thread.start();

        mServiceLooper = thread.getLooper();
        mServiceHandler = new ServiceHandler(mServiceLooper);
    }

    @Override
    public void onDestroy() {
        // Shut down the helper and the looper.
        mHelper.close();
        mServiceLooper.quit();
        
        super.onDestroy();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Here's a trick I picked up from IntentService...
        Message msg = mServiceHandler.obtainMessage();
        msg.arg1 = startId;
        msg.obj = intent;
        mServiceHandler.sendMessage(msg);
        
        // We're not sticky.  We don't want intents re-sent and we call stopSelf
        // whenever we want to stop entirely.
        return Service.START_NOT_STICKY;
    }
    
    /**
     * <p>
     * Handles the Intent sent in.  Specifically, this looks at the Intent,
     * decides if it's a command or a work unit, and then either acts on the
     * command or shoves the Intent into the queue to be processed, starting the
     * queue-working thread if need be.  This gets called on a separate thread
     * from the rest of the GUI (AND a separate thread from the queue worker).
     * The actual application-specific work happens in {@link #handleIntent(Intent)}.
     * </p>
     * 
     * @param intent the incoming Intent
     */
    private void handleCommand(Intent intent) {
        // First, check if this is a command message.
        if(intent.hasExtra(COMMAND_EXTRA)) {
            // If so, take command.  Make sure it's a valid command.
            int command = intent.getIntExtra(COMMAND_EXTRA, -1);
            
            if(!isPaused()) {
                Log.w(DEBUG_TAG, "The queue isn't paused, ignoring the command...");
                return;
            }
            
            if(command == -1) {
                // INVALID!
                Log.w(DEBUG_TAG, "Command Intent didn't have a valid command in it!");
                return;
            }
            
            if(command != COMMAND_RESUME && command != COMMAND_ABORT && command != COMMAND_RESUME_SKIP_FIRST) {
                Log.w(DEBUG_TAG, "I don't know what sort of command " + command + " is supposed to be, ignoring...");
                return;
            }

            // The thread should NOT be active right now!  If it is, we're in
            // trouble!
            if(mThread != null && mThread.isAlive()) {
                Log.e(DEBUG_TAG, "isPaused returned true, but the thread is still alive?  What?");
                // Last ditch effort: Try to interrupt the thread to death.
                mThread.interrupt();
            }
            
            mIsPaused = false;
            
            // It's a good command, send it off!
            if(command == COMMAND_RESUME) {
                // Simply restart the thread.  The queue will start from where
                // it left off.
                Log.d(DEBUG_TAG, "Restarting the thread now...");
                doNewThread();
            } else if(command == COMMAND_RESUME_SKIP_FIRST) {
                Log.d(DEBUG_TAG, "Restarting the thread now, skipping the first Intent...");
                removeNextIntentFromQueue();
                doNewThread();
            } else {
                // This is a COMMAND_ABORT.  Simply empty the queue (but call
                // the callback first).
                Log.d(DEBUG_TAG, "Emptying out the queue (removing " + getQueueCount() + " Intents)...");
                onQueueEmpty(false);
                clearQueue();
                stopSelf();
            }
        } else {
            // If this isn't a control message, add the intent to the queue.
            Log.d(DEBUG_TAG, "Enqueueing an Intent!");
            writeIntentToQueue(intent);
            
            // Next, if the thread isn't already running (AND we're not paused),
            // make it run.  If it IS running, we'll just process the next one
            // in turn.
            if(isPaused() && resumeOnNewIntent()) {
                Log.d(DEBUG_TAG, "Queue was paused, resuming it now!");
                
                if(mThread != null && mThread.isAlive()) {
                    Log.e(DEBUG_TAG, "isPaused returned true, but the thread is still alive?  What?");
                    // Last ditch effort: Try to interrupt the thread to death.
                    mThread.interrupt();
                }
                
                mIsPaused = false;
                doNewThread();
            } else if(!isPaused() && (mThread == null || !mThread.isAlive())) {
                Log.d(DEBUG_TAG, "Starting the thread fresh...");
                doNewThread();
            }
        }
    }
    
    private void doNewThread() {
        // Only call this if the old thread isn't running.
        mThread = new Thread(new QueueThread(), "QueueService Runner");
        mThread.start();
    }

    /* (non-Javadoc)
     * @see android.app.Service#onBind(android.content.Intent)
     */
    @Override
    public IBinder onBind(Intent arg0) {
        return null;
    }
    
    private class QueueThread implements Runnable {

        @Override
        public void run() {
            // Now!  Loop through the queue!
            Intent i;
            
            if(getQueueCount() == 0)
                onQueueStart();
            
            while(getQueueCount() > 0) {
                i = getNextIntentFromQueue();

                Log.d(DEBUG_TAG, "Processing intent...");
                
                ReturnCode r = handleIntent(i);
                
                Log.d(DEBUG_TAG, "Intent processed, return code is " + r);
                
                // Return check!
                if(r == ReturnCode.STOP) {
                    // If the return code we got instructed us to stop entirely,
                    // wipe the queue and bail out.
                    Log.d(DEBUG_TAG, "Return said to stop, stopping now and abandoning " + getQueueCount() + " Intent(s).");
                    onQueueEmpty(false);
                    clearQueue();
                    stopSelf();
                    return;
                } else if(r == ReturnCode.CONTINUE) {
                    // CONTINUE means processing was a success, so we can yoink
                    // the Intent from the front of the queue and scrap it.
                    Log.d(DEBUG_TAG, "Return said to continue.");
                    removeNextIntentFromQueue();
                } else if(r == ReturnCode.PAUSE) {
                    // If we were told to pause, well, pause.  We'll be told to
                    // try again later.
                    Log.d(DEBUG_TAG, "Return said to pause.");

                    mIsPaused = true;
                    onQueuePause(i);
                    return;
                }
            }
            // If we got here, then hey!  The thread's done!
            Log.d(DEBUG_TAG, "Processing complete.");
            onQueueEmpty(true);
            stopSelf();
        }
    }
    
    /**
     * Returns whether or not the queue is currently paused.
     * 
     * @return true if paused, false if not
     */
    public boolean isPaused() {
        return mIsPaused;
    }

    private synchronized SQLiteDatabase initDatabase() throws SQLException {
        // If we already have an open database, use that.  Otherwise, make a new
        // one.
        if(mDatabase != null && mDatabase.isOpen())
            return mDatabase;

        mHelper = new DatabaseHelper(this);
        mDatabase = mHelper.getWritableDatabase();
        return mDatabase;
    }

    private long writeIntentToQueue(@NonNull Intent i) throws SQLException {
        synchronized(this) {
            SQLiteDatabase database = initDatabase();

            // Serialize the Intent, using whatever method the concrete
            // implementation says it should.
            String data = serializeIntent(i);
            if(data == null) data = "";

            // Grab a timestamp!
            long time = Calendar.getInstance().getTimeInMillis();

            // Now, shove it into the database!
            ContentValues toGo = new ContentValues();
            toGo.put(KEY_QUEUE_TIMESTAMP, time);
            toGo.put(KEY_QUEUE_DATA, data);

            return database.insert(TABLE_QUEUE, null, toGo);
        }
    }

    private void removeNextIntentFromQueue() throws SQLException {
        synchronized(this) {
            SQLiteDatabase database = initDatabase();

            // Grab us exactly one entry, if that.
            Cursor cursor = database.query(TABLE_QUEUE, new String[]{KEY_QUEUE_ROWID},
                    null, null, null, null,
                    KEY_QUEUE_TIMESTAMP + " ASC", "1");

            if(cursor == null) {
                // I really hope this never comes up, else a LOT of methods will
                // dump this to logcat.
                Log.w(DEBUG_TAG, "When removing the next Intent, the Cursor was null!");
                return;
            }

            if(cursor.getCount() == 0) {
                Log.i(DEBUG_TAG, "Tried to remove next Intent but there's nothing in the database!");
                return;
            }

            // Otherwise, we have us our row ID.
            cursor.moveToFirst();
            long rowId = cursor.getLong(cursor.getColumnIndex(KEY_QUEUE_ROWID));
            cursor.close();

            database.delete(TABLE_QUEUE, KEY_QUEUE_ROWID + "=" + rowId, null);
        }
    }

    @Nullable
    private Intent getNextIntentFromQueue() throws SQLException {
        synchronized(this) {
            SQLiteDatabase database = initDatabase();

            // We'll delete these when we're done with the Cursor.
            List<Long> toDelete = new LinkedList<>();

            // Grab everything!  Sorted!
            Cursor cursor = database.query(TABLE_QUEUE, new String[]{KEY_QUEUE_ROWID, KEY_QUEUE_DATA},
                    null, null, null, null,
                    KEY_QUEUE_TIMESTAMP + " ASC");

            if(cursor == null) {
                // Problem!
                Log.w(DEBUG_TAG, "When getting the next Intent, the Cursor was null!");
                return null;
            }

            if(cursor.getCount() == 0) {
                // Not really a problem, but the queue's just empty.
                return null;
            }

            cursor.moveToFirst();

            // Now, loop through until we find something we can use (or until
            // we bottom out).
            Intent toReturn = null;

            while(toReturn == null && !cursor.isAfterLast()) {
                // Data!  Now!
                long rowId = cursor.getLong(cursor.getColumnIndex(KEY_QUEUE_ROWID));
                String data = cursor.getString(cursor.getColumnIndex(KEY_QUEUE_DATA));

                // Now, try to deserialize.  This'll be null if it should be
                // ignored.
                toReturn = deserializeIntent(data);

                // And if it IS null, delete it afterward.
                if(toReturn == null)
                    toDelete.add(rowId);

                // The while loop will stop if we found something.  Move on!
                cursor.moveToNext();
            }

            // So!  Let's wrap things up.  Get rid of the cursor.
            cursor.close();

            // Now, delete everything that was null.
            for(Long l : toDelete) {
                database.delete(TABLE_QUEUE, KEY_QUEUE_ROWID + "=" + l, null);
            }

            // And return whatever our result was.  That result may very well be
            // null.
            return toReturn;
        }
    }

    private int getQueueCount() throws SQLException {
        synchronized(this) {
            SQLiteDatabase database = initDatabase();

            // This oughta be easy.
            Cursor cursor = database.query(TABLE_QUEUE, new String[]{KEY_QUEUE_ROWID},
                    null, null, null, null,
                    null);

            if(cursor == null) {
                Log.w(DEBUG_TAG, "When getting the queue count, the Cursor was null!");
                return 0;
            }

            int toReturn = cursor.getCount();
            cursor.close();
            return toReturn;
        }
    }

    private void clearQueue() throws SQLException {
        synchronized(this) {
            // Everybody out of the pool!
            SQLiteDatabase database = initDatabase();

            database.delete(TABLE_QUEUE, null, null);
        }
    }

    /**
     * Called whenever a new data Intent comes in and the queue is paused to
     * determine if the queue should resume immediately.  If this returns false,
     * the queue will remain paused until an explicit {@link #COMMAND_RESUME}
     * command Intent is sent.  Note that the queue will always start if the
     * queue is empty.
     *
     * @return true to resume on a new Intent, false to remain paused
     */
    protected abstract boolean resumeOnNewIntent();

    /**
     * Subclasses get this called every time something from the queue comes in
     * to be processed.  This will not be called on the main thread.  There will
     * be no callback on successful processing of an individual Intent, but
     * {@link #onQueuePause(Intent)} will be called if the queue is paused, and
     * {@link #onQueueEmpty(boolean)} will be called at the end of all processing.
     * 
     * @param i Intent to be processed
     * @return a ReturnCode indicating what the queue should do next
     */
    protected abstract ReturnCode handleIntent(Intent i);
    
    /**
     * This gets called immediately before the first Intent is processed in a
     * given run of QueueService.  That is to say, after the service is started
     * due to an Intent coming in OR every time the service is told to resume
     * after being paused.  {@link #handleIntent(Intent)} will be called after
     * this returns.  This would be a good place to set up wakelocks.
     */
    protected abstract void onQueueStart();
    
    /**
     * <p>
     * This gets called if the queue needs to be paused for some reason.  The
     * Intent that caused the pause will be included.  The thread will be killed
     * after this callback returns.  However, {@link #isPaused()} will return
     * false if called during this callback.  Try not to block it.
     * </p>
     * 
     * <p>
     * Note that you aren't doing the actual pausing here.  This method is just
     * here to do status updates or to inform the user that the queue is paused,
     * which might or might not require more input.  If you need more
     * information as to exactly why the queue was paused, you can always stuff
     * more extras in the Intent during onHandleIntent before it gets here.
     * </p>
     * 
     * <p>
     * Now would be a good time to release that wakelock you made back in
     * {@link #onQueueStart()}.
     * </p>
     * @param i Intent that caused the pause
     */
    protected abstract void onQueuePause(Intent i);
    
    /**
     * <p>
     * This is called right after the queue is done processing and right before
     * the thread is killed and isn't paused.  The boolean indicates if
     * processing was complete.  If false, it means a {@link ReturnCode#STOP}
     * was received or {@link #COMMAND_ABORT} was sent.  The queue will be
     * emptied AFTER this method returns.
     * </p>
     * 
     * <p>
     * This would be another good place to release that {@link #onQueueStart()}
     * wakelock you've been holding onto.  Onto which you've been holding.
     * </p>
     *  
     * @param allProcessed true if the queue emptied normally, false if it was
     *                     aborted before all Intents were processed
     */
    protected abstract void onQueueEmpty(boolean allProcessed);

    /**
     * Returns the name of the SQLite database that'll be used for this queue.
     * Make sure it's unique within your package's context.
     *
     * @return a database name
     */
    @NonNull
    protected abstract String getQueueDatabaseName();

    /**
     * <p>
     * Serializes the given Intent to a String.  Note that at this point, an
     * Intent is solely used as a means of storing data.  This can be called any
     * time an Intent comes in; assume it will be, though there may be cases in
     * which it won't.  If this isn't called for a given Intent, there won't be
     * a corresponding deserialize call.  You can return whatever String you
     * want here, but whatever you return, it'll be your responsibility to
     * deserialize it later in {@link #deserializeIntent(String)}.
     * </p>
     *
     * <p>
     * If this returns null, it will be treated as an empty string and stored in
     * the database as such.  If you choose not to do anything with it, you may
     * return null from {@link #deserializeIntent(String)} later.
     * </p>
     *
     * @param i Intent to serialize
     * @return a String representation of the vital info in the Intent
     * @see #deserializeIntent(String)
     */
    @Nullable
    protected abstract String serializeIntent(@NonNull Intent i);

    /**
     * <p>
     * Deserializes the given String back into an Intent.  This will be called
     * any time work on an Intent finishes and more exist in the queue.  All you
     * have to do is pull back whatever you wrote in {@link #serializeIntent(Intent)}
     * and get an Intent out of it that {@link #handleIntent(Intent)} will deal
     * with.
     * </p>
     *
     * <p>
     * If this returns null, this entry in the queue will be ignored and
     * removed.  {@link #handleIntent(Intent)} will NOT be called on it.
     * </p>
     *
     * @param s a String to deserialize
     * @return an Intent formed by deserializing the input String
     * @see #serializeIntent(Intent)
     */
    @Nullable
    protected abstract Intent deserializeIntent(@NonNull String s);
}

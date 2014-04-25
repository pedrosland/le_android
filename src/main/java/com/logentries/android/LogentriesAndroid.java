package com.logentries.android;


import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.Thread.State;
import java.net.Socket;
import java.net.UnknownHostException;
import java.nio.charset.Charset;
import java.security.KeyManagementException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import org.apache.http.conn.ssl.SSLSocketFactory;

import android.content.Context;
import android.os.Handler.Callback;
import android.os.HandlerThread;
import android.os.Message;
import android.util.Log;

/**
 * @author Mark Lacomber, marklacomber@gmail.com - 22/08/11
 * modified by Caroline Fenlon - 29/08/11
 * 	- added custom SLLSocketFactory
 * 	- added format, upload methods
 * 	- altered publish method
 * modified by Mark - 10/12/12
 * -  changed to Token-based logging
 * -  Asynchronous logging
 * modified by Sean - 07/03/14
 * - added classes Lock, RunnableExecutorThread, FileAppender, FileReader
 * - added method saveLog
 * - altered publish method
 * - altered socketAppender run method, changed from Thread to Runnable
 * VERSION 2.0
 */
public class LogentriesAndroid extends Handler {

	/*
	 * Constants
	 */
	/** Current Version Number. */
	static final String VERSION = "2.0";
	/** Size of the internal event queue. */
	static final int QUEUE_SIZE = 32768;
	/** Logentries API server address */
	static final String LE_API = "api.logentries.com";
	/** Logentries Port number for TLS Token-Based Logging */
	static final int LE_PORT = 20000;
	/** Tag for Logentries Debug Messages to LogCat */
	static final String TAG = "Logentries";
	/** Minimal delay between attempts to reconnect in milliseconds. */
	static final int MIN_DELAY = 100;
	/** Maximal delay between attempts to reconnect in milliseconds. */
	static final int MAX_DELAY = 10000;
	/** UTF-8 output character set. */
	static final Charset UTF8 = Charset.forName( "UTF-8");
	/** Error message displayed when invalid API key is detected. */
	static final String INVALID_TOKEN = "It appears your Token UUID parameter is incorrect!";
	/**The location of the file to be created */
	static final String logFileAddress = "logentries_saved_logs.log";
	/**The length of a timeout */
	static final long timeout= 1000;
	/**The unit of time of a timeout */
	static final TimeUnit milliseconds= TimeUnit.MILLISECONDS;
    /** Unicode character for newline */
    static final char UNICODE_NEWLINE = 0x2424;
	/*
	 * Fields
	 */
	/** Destination token */
	String m_token;
	/** Debug flag */
	boolean debug;
	/** Indicator if the socket appender has been started. */
	boolean startedSocketAppender;
	/** Indicator if a FileRead Runnable is running in a thread. */
	boolean readingStarted;
    /** Indicator if the file appender has been started. */
    boolean startedFileAppender;
	/**lock for preventing simultaneous reading and writing of the log file*/
	Lock fileLock;
	/** Context inherited from Activity/Application */
	Context m_context;

    /** Determines if entries are uploaded immediately or saved to file for upload later */
    private boolean immediateUpload = true;
    /** Online status */
    private boolean isOnline = true;

	/** Asynchronous socket appender, Runnable */
	SocketAppender appender;
	/** File appender Thread */
	FileAppender fileAppender;
	/** Runnable File reader */
	FileReader fileReader;
	/** Thread for Running File reader */
	RunnableExecutorThread fileReadingThread;
	/** Thread for Running uploads */
	RunnableExecutorThread socketAppendingThread;
	/** Message queue for uploads. */
	ArrayBlockingQueue<String> uploadQueue;
	/** Message queue for saving to file. */
	ArrayBlockingQueue<String> saveQueue;
	File dir,file;

	/*
	 * Internal classes
	 */
	/**
	 *  A lock class for synchronization between threads
	 * @author Sean
	 */
	class Lock{
		private boolean isLocked = false;

		public synchronized void lock()
				throws InterruptedException{
			while(isLocked){
				wait();
			}
			isLocked = true;
		}
		public synchronized void unlock(){
			isLocked = false;
			notify();
		}
	}
	/**
	 * A thread that executes runnables passed to it
	 * @author Sean
	 */
	class RunnableExecutorThread extends HandlerThread implements Callback {

		private android.os.Handler mHandler;

		public RunnableExecutorThread() {
			super("RunnableExecutorThread");
			// Don't block shut down
			setDaemon(true);
		}

		public void doRunnable(Runnable runnable) {
			if (mHandler == null) {
				mHandler = new android.os.Handler(getLooper(), this);
			}
			Message msg = mHandler.obtainMessage(0, runnable);
			mHandler.sendMessage(msg);
		}

		@Override
		public boolean handleMessage(Message msg) {
			Runnable runnable = (Runnable) msg.obj;
			runnable.run();
			return true;
		}
	}
	/**
	 * Thread that appends logs to a file
	 * @author Sean
	 */
	class FileAppender extends Thread{
		FileAppender(){
			super("File Appending Thread");
			setDaemon(true);
		}

		/**
		 * Open stream to file
		 * Await data from Queue
		 * Wait for lock on file
		 * Write data
		 * Release Lock
		 */
		public void run(){
			FileOutputStream fos = null;
			try {
				fos = m_context.openFileOutput(logFileAddress, Context.MODE_APPEND);
				while(true){
					String data = saveQueue.take();
					fileLock.lock();
					fos.write((data).getBytes());
					fileLock.unlock();
				}
			} catch (IOException e) {
				e.printStackTrace();
			} catch(NullPointerException e) {
				e.printStackTrace();
			} catch (InterruptedException e) {
				e.printStackTrace();
			}finally{
				if(fos != null){
					try {
						fos.close();
					} catch (IOException e) {
						e.printStackTrace();
					}
				}
			}

            startedFileAppender = false;
		}
	}
	/**
	 * Runnable to read logs from file and send to be uploaded
	 * @author Sean
	 *
	 */
	class FileReader extends Thread{
        FileReader(){
            super("File Reader Thread");
            setDaemon(true);
        }

		public void run(){
			try{
				//Thread.sleep(50);
				BufferedReader d = new BufferedReader(new InputStreamReader(m_context.openFileInput(logFileAddress)));
				String log;

                while(true) {
                    fileLock.lock();
                    log = d.readLine();
                    fileLock.unlock();

                    if(log == null) {
                        break;
                    }

                    uploadQueue.offer(log + "\r\n", timeout, milliseconds);
                }

                file.delete();
				fileLock.unlock();
                immediateUpload = true;

//                stopFileReaderThread();
                stopFileAppenderThread();
			} catch (FileNotFoundException e) {
				dbg("File not found");
			} catch (IOException e) {
				e.printStackTrace();
			} catch (InterruptedException e) {
				e.printStackTrace();
			}

            readingStarted = false;
		}
	}
	/**
	 * Asynchronous over the socket appender
	 *
	 * @author Mark Lacomber
	 * Edited by Sean - 07/03/14
	 * - Changed from Thread to Runnable
	 * - When exception is thrown during writing the data, it is sent to be saved to file
	 */
	class SocketAppender implements Runnable {
		/** Socket connection. */
		Socket s;
		/** SSLSocket connection. */
		SSLSocket socket;
		/** SSLSocketFactory for sslsocket. */
		SSLSocketFactory socketFactory;
		/** Output log stream. */
		OutputStream stream;
		/** Random number generator for delays between reconnection attempts. */
		final Random random = new Random();
		String data=null;


		/**
		 * Opens connection to Logentries
		 *
		 * @throws java.io.IOException
		 * @throws java.security.cert.CertificateException
		 */
		void openConnection() throws IOException {
			try{
				dbg( "Reopening connection to Logentries API server");

				KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
				trustStore.load(null, null);

				socketFactory = new EasySSLSocketFactory(trustStore);
				socketFactory.setHostnameVerifier(SSLSocketFactory.ALLOW_ALL_HOSTNAME_VERIFIER);
				s = new Socket(LE_API, LE_PORT);
				socket = (SSLSocket)socketFactory.createSocket(s, "", 0, true);
				socket.setTcpNoDelay(true);
				stream = socket.getOutputStream();

				dbg( "Connection established");
			} catch (Exception e){
				e.printStackTrace();
			}
		}

		/**
		 * Tries to open connection to Logentries until it succeeds
		 *
		 * @throws InterruptedException
		 */
		void reopenConnection() throws InterruptedException {
			// Close the previous connection
			closeConnection();

			//Try to open the connection until we get through
			int root_delay = MIN_DELAY;
			while(true)
			{
				try{
					openConnection();
					// Success, leave
					return;
				} catch (IOException e) {
					// Get information if in debug mode
					dbg( "Unable to connect to Logentries");
				}

				// Wait between connection attempts
				root_delay *= 2;
				if (root_delay > MAX_DELAY)
					root_delay= MAX_DELAY;
				int wait_for = root_delay + random.nextInt( root_delay);
				dbg( "Waiting for " + wait_for + "ms");
				Thread.sleep( wait_for);
			}
		}

		/**
		 * Closes the connection. Ignore errors
		 */
		void closeConnection() {
			if (stream != null){
				try{
					stream.close();
				} catch (IOException e){
					// Nothing we can do here
				}
			}
			stream = null;
			if (socket != null) {
				try{
					socket.close();
				} catch (IOException e){
					// Nothing we can do here
				}
			}
			socket = null;
		}

		/**
		 * Initializes the connection and starts to log
		 */
		@Override
		public void run(){
			try{
				// Open connection
				openConnection();

				// Send data in queue
				while (true) {
					// Take data from queue
					
					data = uploadQueue.take();
					String dataWithToken = m_token + data;
					dataWithToken = dataWithToken.trim().replace('\n', UNICODE_NEWLINE) + '\n';
					byte[] msg = dataWithToken.getBytes("UTF8");
					// Send data, save to file on failure
                    try{
                        stream.write( msg);
                        stream.flush();
                    } catch (IOException e) {
                        reopenConnection();
                    }
				}
			} catch (Exception e){
				// We got interrupted
				dbg( "Asynchronous socket writer interrupted");
				//get lost item
				try {
					saveQueue.offer(data,timeout,milliseconds);
				} catch (InterruptedException e2) {
					e2.printStackTrace();
				}
				//copy upload queue to saveQueue to preserve the logs
				while(uploadQueue.peek() != null){
					try {
						saveQueue.offer(uploadQueue.poll(),timeout,milliseconds);
					} catch (InterruptedException e1) {
						e1.printStackTrace();
					}
				}
				startedSocketAppender=false;
			}
			closeConnection();
		}
	}

	/**
	 * custom Android SSLSocketFactory
	 */
	class EasySSLSocketFactory extends SSLSocketFactory {
		SSLContext sslContext = SSLContext.getInstance("TLS");

		public EasySSLSocketFactory(KeyStore keystore) throws NoSuchAlgorithmException,
		KeyManagementException, KeyStoreException, UnrecoverableKeyException {

			super(keystore);

			TrustManager manager = new X509TrustManager() {
				public void checkClientTrusted(X509Certificate[] chain,
						String authType) throws CertificateException {
				}

				public void checkServerTrusted(X509Certificate[] chain,
						String authType) throws CertificateException {
				}

				public X509Certificate[] getAcceptedIssuers() {
					return null;
				}
			};
			sslContext.init(null, new TrustManager[]{ manager }, null);
		}

		@Override
		public Socket createSocket(Socket socket, String host, int port,
				boolean autoClose) throws IOException, UnknownHostException {
			return sslContext.getSocketFactory().createSocket(socket, host, port,
					autoClose);
		}

		@Override
		public Socket createSocket() throws IOException {
			return sslContext.getSocketFactory().createSocket();
		}
	}

	public LogentriesAndroid( String token, boolean debug, Context context, boolean isOnline)
	{
		this.m_token = token;
		this.debug = debug;
        this.m_context = context;
        this.isOnline = isOnline;

		uploadQueue = new ArrayBlockingQueue<String>( QUEUE_SIZE);
		saveQueue = new ArrayBlockingQueue<String>( QUEUE_SIZE);

		dir = m_context.getFilesDir();
		file = new File(dir, logFileAddress);

        immediateUpload = !file.exists() && isOnline;

		fileLock=new Lock();

		//runnables
		appender = new SocketAppender();
		fileReader = new FileReader();

		//threads
		fileAppender = new FileAppender();
		fileReadingThread = new RunnableExecutorThread();
		socketAppendingThread = new RunnableExecutorThread();

		//control booleans
		startedSocketAppender = false;
		readingStarted = false;

        setup();
	}


    /**
     * @param isOnline true if events are to be uploaded to Logentries immediately
     */
    public void setImmediateUpload(boolean isOnline) {
        this.isOnline = isOnline;
        immediateUpload = false;

        if(file.exists()){
            if(isOnline && !readingStarted) {
                // If we have a backlog, network activity and the backlog uploader isn't running,
                // start it.
                startFileReaderThread();
            }
        }else if(isOnline){
            immediateUpload = true;

//            stopFileReaderThread();
        }
    }

    /**
     * @return true if events are to be uploaded immediately, false otherwise
     * default value: true
     */
    public boolean getImmediateUpload() {
        return immediateUpload;
    }

	/**
	 * Checks that key and location are set.
	 */
	public boolean checkCredentials() {
		if (m_token == null)
			return false;

		//Quick test to see if LOGENTRIES_TOKEN is a valid UUID
		UUID u = UUID.fromString(m_token);
		if (!u.toString().equals(m_token))
		{
			dbg(INVALID_TOKEN);
			return false;
		}

		return true;
	}

    public void setup() {
        //start up the threads if they are not running
        if (socketAppendingThread.getState()==State.NEW && checkCredentials()) {
            dbg( "Starting Logentries asynchronous socket appender");
            socketAppendingThread.start();
        }

        //if we are not trying to upload then start uploading
        if(!startedSocketAppender){
            startedSocketAppender = true;
            socketAppendingThread.doRunnable(appender);
        }

        if(file.exists() && isOnline) {
            startFileReaderThread();
        }
        if(!immediateUpload){
            startFileAppenderThread();
        }
    }

    public void startFileReaderThread() {
        if(fileReader.getState() == State.TERMINATED){
            fileReader = new FileReader();
        }

        if(fileReader.getState() == State.NEW) {
            fileReader.start();
        }

        readingStarted = true;
    }

    public void startFileAppenderThread() {
        if(fileAppender.getState() == State.TERMINATED){
            fileAppender = new FileAppender();
        }

        if(fileAppender.getState() == State.NEW){
            fileAppender.start();
        }

        startedFileAppender = true;
    }

//    public void stopFileReaderThread() {
//        if(fileReadingThread.getState() != State.NEW && fileReadingThread.getState() != State.TERMINATED){
//            fileReadingThread.interrupt();
//        }
//        readingStarted = false;
//    }

    public void stopFileAppenderThread() {
        if(fileAppender.getState() != State.NEW && fileAppender.getState() != State.TERMINATED){
            fileAppender.interrupt();
        }
    }

    /**
	 * format and upload a LogRecord, if everything is okay.
	 * if there is a file of saved logs then write to and read from it 
	 * until empty, to preserve log order.
	 * @param record the LogRecord to upload
	 */
	public void publish(LogRecord record) {
		Date dateTime = new Date(record.getMillis());

		String MESSAGE = this.format(dateTime, record.getMessage(), record.getLevel());

		//to preserve ordering of logs
		//if there is a file then it must be written to and read from until empty
		if(!immediateUpload){
			try {//append the latest data to the file
				saveQueue.offer(MESSAGE, timeout,milliseconds);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}

            if(!startedFileAppender) {
                startFileAppenderThread();
            }
		}
		else{
			boolean successful_add = false;
			try {//try adding to upload queue
				successful_add = uploadQueue.offer( MESSAGE, timeout,milliseconds);
			} catch (InterruptedException e1) {
				e1.printStackTrace();
			}
			//if that fails add it to the saveQueue
			if(!successful_add){
				try {
					saveQueue.offer(MESSAGE, timeout,milliseconds);
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
			}
		}
	}
	/**
	 * Called when there is no Internet connection
	 * passes event to the queue for it to be saved
	 * @param event the formatted log report to save
	 */
	public void saveLog(String event) {
		//start up file appender if it is not already running
		if(fileAppender.getState()==State.NEW){
			fileAppender.start();
		}
		try {
			saveQueue.offer(event,timeout,milliseconds);
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
	}


	/**
	 * @param date log time
	 * @param logData log message
	 * @param level log severity level
	 * @return eg. <i>Mon 29 Aug 09:06:48 +0000 2011, severity=DEBUG, 	log message</i>
	 */
	public String format(Date date, String logData, Level level) {
		SimpleDateFormat sdf = new SimpleDateFormat("EEE d MMM HH:mm:ss Z yyyy");
        logData = logData.replace('\n', UNICODE_NEWLINE);
		String log = sdf.format(date) + ", severity=" + level.toString() + ", " + logData + "\n";
		return log;
	}

	public void dbg(String debugMessage)
	{
		if (debug)
		{
			Log.e(TAG, debugMessage);
		}
	}

	@Override
	/**
	 * Interrupts the background logging thread
	 */
	public void close() {
		// Interrupt the background thread
		socketAppendingThread.interrupt();

        //TODO: do we need to do this to the other threads?
	}

	@Override
	public void flush() {
		// Don't need to do anything here
	}

	
}


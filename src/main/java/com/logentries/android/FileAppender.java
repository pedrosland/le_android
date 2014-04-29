package com.logentries.android;

/**
 * Created by peter on 29/04/14.
 */

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;

/**
 * Runnable to read logs from file and send to be uploaded
 * @author Sean
 *
 */
public class FileReader extends Thread{

    InputStream inputStream;

    FileReader(InputStream inputStream){
        super("File Reader Thread");
        setDaemon(true);

        this.inputStream = inputStream;
    }

    public void run(){
        try{
            //Thread.sleep(50);
            BufferedReader d = new BufferedReader(new InputStreamReader(inputStream));
            String log;

            while(!isInterrupted()) {
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

            stopFileAppenderThread();
        } catch (FileNotFoundException e) {
            dbg("File not found");
        } catch (IOException e) {
            e.printStackTrace();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        startedFileReader = false;
    }
}

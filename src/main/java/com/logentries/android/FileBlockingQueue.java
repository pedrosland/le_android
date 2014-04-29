package com.logentries.android;

import com.squareup.tape.QueueFile;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.AbstractQueue;
import java.util.Collection;
import java.util.Iterator;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Created by peter on 29/04/14.
 */
public class FileBlockingQueue extends AbstractQueue<String> implements BlockingQueue<String> {

    private final ReentrantLock lock;
    File file;

    int maxSize;

    FileOutputStream outputStream;
    FileInputStream inputStream;

    Charset fileCharset;

    ArrayBlockingQueue<java.lang.String> readQueue;
    ArrayBlockingQueue<java.lang.String> writeQueue;

    QueueFile queueFile;

    int MAX_QUEUE_SIZE = 100;

    public FileBlockingQueue(File file, int maxSize) throws IOException {
        this.file = file;
        this.maxSize = maxSize;

        this.lock = new ReentrantLock();

        this.fileCharset = Charset.forName("UTF-8");

        writeQueue = new ArrayBlockingQueue<java.lang.String>(MAX_QUEUE_SIZE);
        readQueue  = new ArrayBlockingQueue<java.lang.String>(MAX_QUEUE_SIZE);

        queueFile = new QueueFile(file);
    }

    @Override
    public Iterator<String> iterator() {
        return null;
    }

    @Override
    public int size() {
        return 0;
    }

    /**
     * Returns the size of queue file
     */
    public long usedBytes() {
        return file.length();
    }

    @Override
    public void put(String string) throws InterruptedException {
        queueFile.add(str.toByteArray(fileCharset));

        try {
            while (queueFile.size() > maxSize) {
                queueFile.remove();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Override
    public boolean offer(String string, long timeout, TimeUnit unit) throws InterruptedException {
        return writeQueue.offer()
    }

    @Override
    public boolean offer(String string) {
        return writeQueue.offer(string);
    }

    private byte[] serialize(java.lang.String string) {
        return string.getBytes(fileCharset);
    }

    @Override
    public String take() throws InterruptedException {
        byte[] item;
        try {
            item = queueFile.peek();
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
        return new String(item, fileCharset);
    }

    @Override
    public String poll(long timeout, TimeUnit unit) throws InterruptedException {
        return null;
    }

    @Override
    public String poll() {
        return null;
    }

    @Override
    public int remainingCapacity() {
        return 0;
    }

    @Override
    public int drainTo(Collection<? super String> c) {
        return 0;
    }

    @Override
    public int drainTo(Collection<? super String> c, int maxElements) {
        return 0;
    }

    @Override
    public String peek() {
        try {
            return queueFile.peek();
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
    }
}

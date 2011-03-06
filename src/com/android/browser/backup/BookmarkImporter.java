package com.android.browser.backup;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Scanner;
import java.util.Set;
import java.util.Vector;


import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.DialogInterface;
import android.content.DialogInterface.OnCancelListener;
import android.content.DialogInterface.OnClickListener;
import android.net.Uri;
import android.os.Handler;
import android.os.PowerManager;
import android.provider.Browser;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.RelativeSizeSpan;

import com.android.browser.R;

public class BookmarkImporter {

    private final static String LOG_TAG = "BookmarkImporter";

    private Context mParentContext;

    private ProgressDialog mProgressDialog;

    private Handler mHandler = new Handler();

    private static final String SMS_COL_SEPARATOR = "\r\n";
    private static final String SMS_DATA_SEPARATOR = ":";


    private static final String SMS_PROPERTY_BEGIN = "BEGIN";
    private static final String SMS_PROPERTY_END = "END";
    private static final String SMS_PROPERTY_VERSION = "VERSION";

    private static final String SMS_DATA_SMS = "ANDROID_SMS_BACKUP";
    private static final String SMS_DATA_VERSION_V01 = "0.1";

    private final String FILE_NAME_EXTENSION = "bkm";

    private List<BookmarkData> mSmsDataList;

    public BookmarkImporter(Context ctx){
        mParentContext = ctx;		
    }


    class BookmarkFile {
        private String mName;
        private String mCanonicalPath;
        private long mLastModified;

        public BookmarkFile(String name, String canonicalPath, long lastModified) {
            mName = name;
            mCanonicalPath = canonicalPath;
            mLastModified = lastModified;
        }

        public String getName() {
            return mName;
        }

        public String getCanonicalPath() {
            return mCanonicalPath;
        }

        public long getLastModified() {
            return mLastModified;
        }
    }

    private class BookmarkReadThread extends Thread
            implements DialogInterface.OnCancelListener {
            private String mCanonicalPath;
            private ContentResolver mResolver;
            private boolean mCanceled;
            private PowerManager.WakeLock mWakeLock;



            public BookmarkReadThread(String canonicalPath) {
                mCanonicalPath = canonicalPath;		   

                mResolver = mParentContext.getContentResolver();
                PowerManager powerManager = (PowerManager)mParentContext.getSystemService(
                        Context.POWER_SERVICE);
                mWakeLock = powerManager.newWakeLock(
                        PowerManager.SCREEN_DIM_WAKE_LOCK |
                        PowerManager.ON_AFTER_RELEASE, LOG_TAG);

            }

            @Override
                public void finalize() {
                    if (mWakeLock != null && mWakeLock.isHeld()) {
                        mWakeLock.release();
                    }
                }

            public void onCancel(DialogInterface dialog) {
                mCanceled = true;
            }

            @Override
                public void run() {
                    mWakeLock.acquire();
                    // Some malicious vCard data may make this thread broken
                    // (e.g. OutOfMemoryError).
                    // Even in such cases, some should be done.
                    try {
                        if (mCanonicalPath != null) {
                            //mProgressDialog.setProgressNumberFormat("");
                            mProgressDialog.setProgress(0);                 

                            mSmsDataList = parserSmsDataFile(mCanonicalPath);                    
                            mProgressDialog.dismiss();

                            mHandler.post(new Runnable() {
                                public void run() {
                                    String title = mParentContext.getString(R.string.write_bookmark_title);
                                    String message = mParentContext.getString(R.string.write_bookmark_message);
                                    BookmarkWriteThread thread = new BookmarkWriteThread();
                                    showRestoreSmsDialog(title,message,thread);
                                    thread.start();
                                }
                            });

                        } 

                    } finally {
                        mWakeLock.release();
                        mProgressDialog.dismiss();
                    }
                }


            private int counterFileLine(String fileName){
                DataInputStream in = null;
                FileInputStream fstream;
                int lineNum = 0;
                try {
                    fstream = new FileInputStream(fileName);
                    // Get the object of DataInputStream
                    in = new DataInputStream(fstream);

                    BufferedReader brCounter = new BufferedReader(new InputStreamReader(in));


                    while(brCounter.readLine() != null){
                        lineNum ++;
                    }
                    in.close();

                } catch (FileNotFoundException e) {
                    // TODO Auto-generated catch block
                    e.printStackTrace();
                } catch (IOException e) {
                    // TODO Auto-generated catch block
                    e.printStackTrace();
                }

                return lineNum;

            }

            private void incrementProgres(int lineNum,int currentLine){
                int step = lineNum/100;

                if(lineNum >100 && currentLine%step == 0){
                    mProgressDialog.incrementProgressBy(1);
                }else if(lineNum < 100){
                    mProgressDialog.incrementProgressBy(100/lineNum);
                }
            }

            List<BookmarkData> parserSmsDataFile(String fileName){
                List<BookmarkData> dataList = new Vector<BookmarkData>();
                DataInputStream in = null;
                try {

                    FileInputStream fstream = new FileInputStream(fileName);
                    // Get the object of DataInputStream
                    in = new DataInputStream(fstream);

                    int lineNum = counterFileLine(fileName),i = 0;


                    //mProgressDialog.setProgressNumberFormat(
                    //        getString(R.string.reading_vcard_contacts));

                    mProgressDialog.setIndeterminate(false);    		    
                    mProgressDialog.setMax(100); 


                    in = new DataInputStream(fstream);
                    BufferedReader br = new BufferedReader(new InputStreamReader(in));
                    String strLine;
                    boolean entryStarted = false;
                    BookmarkData entry = null ;

                    //Read File Line By Line
                    while ((strLine = br.readLine()) != null)   {

                        if(mCanceled){
                            return dataList;
                        }

                        i++; 
                        incrementProgres(lineNum, i);

                        String name = null;
                        String value = null;
                        int pos = strLine.indexOf(SMS_DATA_SEPARATOR);
                        if(pos != -1){
	                        name = strLine.substring(0,pos).trim();
	                        value = strLine.substring(pos+1,strLine.length()).trim();
                        }                        
                        
                        if(name == null)
                            continue;

                        if(name.equals(SMS_PROPERTY_BEGIN)){
                            entryStarted = true;
                            entry = new BookmarkData();		        	
                        }

                        if(name.equals(SMS_PROPERTY_END)){
                            entryStarted = false;
                            dataList.add(entry);
                            entry = null;
                        }		        

                        if(name.equals(BookmarkData.TITLE) && entryStarted){
                            entry.mTitle = value;
                        }

                        if(name.equals(BookmarkData.URL) && entryStarted){
                            entry.mUrl = value;
                        }

                    }

                    in.close();
                }catch (FileNotFoundException e) {
                    // TODO Auto-generated catch block
                    e.printStackTrace();
                } catch (NumberFormatException e) {
                    // TODO Auto-generated catch block
                    e.printStackTrace();
                } catch (IOException e) {
                    // TODO Auto-generated catch block
                    e.printStackTrace();
                }

                return dataList;    	
            }
    }

    private class BookmarkWriteThread extends Thread
            implements DialogInterface.OnCancelListener {
            private ContentResolver mResolver;
            private boolean mCanceled;
            private PowerManager.WakeLock mWakeLock;

            public BookmarkWriteThread() {

                mResolver = mParentContext.getContentResolver();
                PowerManager powerManager = (PowerManager)mParentContext.getSystemService(
                        Context.POWER_SERVICE);
                mWakeLock = powerManager.newWakeLock(
                        PowerManager.SCREEN_DIM_WAKE_LOCK |
                        PowerManager.ON_AFTER_RELEASE, LOG_TAG);

            }

            @Override
                public void finalize() {
                    if (mWakeLock != null && mWakeLock.isHeld()) {
                        mWakeLock.release();
                    }
                }

            public void onCancel(DialogInterface dialog) {
                mCanceled = true;
            }

            @Override
                public void run() {
                    mWakeLock.acquire();

                    try {

                        //mProgressDialog.setProgressNumberFormat("");
                        mProgressDialog.setProgress(0);

                        //mProgressDialog.setProgressNumberFormat(
                        //        getString(R.string.reading_vcard_contacts));
                        mProgressDialog.setIndeterminate(false);
                        mProgressDialog.setMax(mSmsDataList.size());

                        ContentResolver resolver = mParentContext.getContentResolver();

                        for(int i =0; i < mSmsDataList.size(); i++ ){
                            if(mCanceled)
                                return;

                            ContentValues values = new ContentValues();
                            values.put(Browser.BookmarkColumns.TITLE, mSmsDataList.get(i).mTitle);
                            values.put(Browser.BookmarkColumns.URL, mSmsDataList.get(i).mUrl);
                            values.put(Browser.BookmarkColumns.BOOKMARK,1);

                            resolver.insert( Browser.BOOKMARKS_URI, values);

                            mProgressDialog.incrementProgressBy(1);
                        }		            
                        mProgressDialog.dismiss();       

                    } finally {
                        mWakeLock.release();
                        mProgressDialog.dismiss();
                    }
                }
    }

    private void showRestoreSmsDialog(String title, String message, DialogInterface.OnCancelListener listener) {
        mProgressDialog = new ProgressDialog(mParentContext);
        mProgressDialog.setTitle(title);
        mProgressDialog.setMessage(message);
        mProgressDialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        mProgressDialog.setOnCancelListener(listener);        
        mProgressDialog.show();
    }

    private void importOneBookmarkFromSDCard(String canonicalPath) {
        String title = mParentContext.getString(R.string.reading_bookmark_title);
        String message = mParentContext.getString(R.string.reading_bookmarks_message);
        BookmarkReadThread thread = new BookmarkReadThread(canonicalPath);
        showRestoreSmsDialog(title,message,thread);
        thread.start();
    }


    private class BookmarksSelectedListener implements DialogInterface.OnClickListener {
        private List<BookmarkFile> mSmsFileList;
        private int mCurrentIndex;

        public BookmarksSelectedListener(List<BookmarkFile> smsFileList) {
            mSmsFileList = smsFileList;
            mCurrentIndex = 0;
        }

        public void onClick(DialogInterface dialog, int which) {
            if (which == DialogInterface.BUTTON_POSITIVE) {
                importOneBookmarkFromSDCard(mSmsFileList.get(mCurrentIndex).getCanonicalPath());
            } else if (which == DialogInterface.BUTTON_NEGATIVE) {
                return;
            } else {
                // Some file is selected.
                mCurrentIndex = which;
            }
        }
    }

    private void showBookmarkFileSelectDialog(List<BookmarkFile> smsFileList) {
        int size = smsFileList.size();
        DialogInterface.OnClickListener listener = 
            new BookmarksSelectedListener(smsFileList);
        AlertDialog.Builder builder =
            new AlertDialog.Builder(mParentContext)
            .setTitle(R.string.select_bookmark_title)
            .setPositiveButton(android.R.string.ok, listener)
            .setOnCancelListener(null)
            .setNegativeButton(android.R.string.cancel, null);

        CharSequence[] items = new CharSequence[size];
        DateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        for (int i = 0; i < size; i++) {
            BookmarkFile smsFile = smsFileList.get(i);
            SpannableStringBuilder stringBuilder = new SpannableStringBuilder();
            stringBuilder.append(smsFile.getName());
            stringBuilder.append('\n');
            int indexToBeSpanned = stringBuilder.length();
            // Smaller date text looks better, since each file name becomes easier to read.
            // The value set to RelativeSizeSpan is arbitrary. You can change it to any other
            // value (but the value bigger than 1.0f would not make nice appearance :)
            stringBuilder.append(
                    "(" + dateFormat.format(new Date(smsFile.getLastModified())) + ")");
            stringBuilder.setSpan(
                    new RelativeSizeSpan(0.7f), indexToBeSpanned, stringBuilder.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            items[i] = stringBuilder;
        }
        builder.setSingleChoiceItems(items, 0, listener);
        builder.show();
    }

    private class BookmarkScanThread extends Thread implements OnCancelListener, OnClickListener {
        private boolean mCanceled;
        private boolean mGotIOException;
        private File mRootDirectory;

        // null when search operation is canceled.
        private List<BookmarkFile> mBookmarkFiles;

        // To avoid recursive link.
        private Set<String> mCheckedPaths;
        private PowerManager.WakeLock mWakeLock;

        private class CanceledException extends Exception {
        }

        public BookmarkScanThread(File sdcardDirectory) {
            mCanceled = false;
            mGotIOException = false;
            mRootDirectory = sdcardDirectory;
            mCheckedPaths = new HashSet<String>();
            mBookmarkFiles = new Vector<BookmarkFile>();
            PowerManager powerManager = (PowerManager)mParentContext.getSystemService(
                    Context.POWER_SERVICE);
            mWakeLock = powerManager.newWakeLock(
                    PowerManager.SCREEN_DIM_WAKE_LOCK |
                    PowerManager.ON_AFTER_RELEASE, LOG_TAG);
        }

        @Override
            public void run() {
                try {
                    mWakeLock.acquire();
                    getBookmarkFileRecursively(mRootDirectory);
                } catch (CanceledException e) {
                    mCanceled = true;
                } catch (IOException e) {
                    mGotIOException = true;
                } finally {
                    mWakeLock.release();
                }

                if (mCanceled) {
                    mBookmarkFiles = null;
                }

                mProgressDialog.dismiss();

                if (mGotIOException) {
                    mHandler.post(new Runnable() {
                        public void run() {
                            displayScanErrorMessage(mParentContext.getString(R.string.fail_reason_io_error));
                        }
                    });
                } else if (mCanceled) {
                    return;
                } else {
                    mHandler.post(new Runnable() {
                        public void run() {
                            int size = mBookmarkFiles.size();
                            if(size == 0){
                                displayScanErrorMessage(mParentContext.getString(R.string.fail_reason_no_bookmark_file));
                            }else{
                                showBookmarkFileSelectDialog(mBookmarkFiles);
                            }
                        }
                    });
                }
            }

        private void displayScanErrorMessage(String failureReason) {
            new AlertDialog.Builder(mParentContext)
                .setTitle(R.string.scanning_sdcard_failed_title)
                .setMessage(mParentContext.getString(R.string.scanning_sdcard_failed_message,
                            failureReason))
                .setPositiveButton(android.R.string.ok, null)
                .show();
        }

        private void getBookmarkFileRecursively(File directory)
            throws CanceledException, IOException {
            if (mCanceled) {
                throw new CanceledException();
            }

            for (File file : directory.listFiles()) {
                if (mCanceled) {
                    throw new CanceledException();
                }
                String canonicalPath = file.getCanonicalPath();
                if (mCheckedPaths.contains(canonicalPath)) {
                    continue;
                }

                mCheckedPaths.add(canonicalPath);

                if (file.isDirectory()) {
                    //getBookmarkFileRecursively(file);
                } else if (canonicalPath.toLowerCase().endsWith("." + FILE_NAME_EXTENSION) &&
                        file.canRead()){
                    String fileName = file.getName();
                    BookmarkFile smsFile = new BookmarkFile(
                            fileName, canonicalPath, file.lastModified());
                    mBookmarkFiles.add(smsFile);
                        }
            }
        }

        public void onCancel(DialogInterface dialog) {
            mCanceled = true;
        }

        public void onClick(DialogInterface dialog, int which) {
            if (which == DialogInterface.BUTTON_NEGATIVE) {
                mCanceled = true;
            }
        }
    }

    public void startImportBookmarkFromSdCard() {
        File file = new File("/sdcard");
        if (!file.exists() || !file.isDirectory() || !file.canRead()) {
            new AlertDialog.Builder(mParentContext)
                .setTitle(R.string.no_sdcard_title)
                .setIcon(android.R.drawable.ic_dialog_alert)
                .setMessage(R.string.no_sdcard_message)
                .setOnCancelListener(null)
                .setPositiveButton(android.R.string.ok, null)
                .show();
        } else {
            String title = mParentContext.getString(R.string.searching_bookmark_title);
            String message = mParentContext.getString(R.string.searching_bookmark_message);

            mProgressDialog = ProgressDialog.show(mParentContext, title, message, true, false);
            BookmarkScanThread thread = new BookmarkScanThread(file);
            mProgressDialog.setOnCancelListener(thread);
            thread.start();
        }
    }
}

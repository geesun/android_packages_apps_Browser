package com.android.browser.backup;

public class BookmarkData {

    public final static String TITLE = "TITLE" ;
    public final static String URL = "URL"; 

    public String mTitle; 
    public String mUrl;	

    BookmarkData(String title,String url ){
        mTitle = title;
        mUrl = url;
    }

    BookmarkData(){

    }
}

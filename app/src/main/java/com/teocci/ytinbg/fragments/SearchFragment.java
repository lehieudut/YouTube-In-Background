package com.teocci.ytinbg.fragments;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.support.v4.app.ListFragment;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.ProgressBar;
import android.widget.Toast;

import com.nhaarman.listviewanimations.appearance.simple.SwingBottomInAnimationAdapter;
import com.nhaarman.listviewanimations.itemmanipulation.DynamicListView;
import com.teocci.ytinbg.BackgroundAudioService;
import com.teocci.ytinbg.R;
import com.teocci.ytinbg.VideosAdapter;
import com.teocci.ytinbg.YouTubeSearch;
import com.teocci.ytinbg.YouTubeVideo;
import com.teocci.ytinbg.database.YouTubeSqlDb;
import com.teocci.ytinbg.interfaces.YouTubeVideosReceiver;
import com.teocci.ytinbg.utils.Config;
import com.teocci.ytinbg.utils.NetworkConf;

import java.util.ArrayList;
import java.util.List;

/**
 * Class that handles list of the videos searched on YouTube
 * Created by Teocci on 7.3.16..
 */
public class SearchFragment extends ListFragment implements YouTubeVideosReceiver {

    private static final String TAG = "SearchFragment";

    private DynamicListView videosFoundListView;
    private Handler handler;
    private ArrayList<YouTubeVideo> searchResultsList;
    private ArrayList<YouTubeVideo> scrollResultsList;
    private VideosAdapter videoListAdapter;
    private YouTubeSearch youTubeSearch;
    private ProgressBar loadingProgressBar;
    private NetworkConf networkConf;

    private int onScrollIndex = 0;
    private int mPrevTotalItemCount = 0;

    public SearchFragment() {
        // Required empty public constructor
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        handler = new Handler();
        searchResultsList = new ArrayList<>();
        scrollResultsList = new ArrayList<>();
        networkConf = new NetworkConf(getActivity());
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        // Inflate the layout for this fragment
        View v = inflater.inflate(R.layout.fragment_search, container, false);
        loadingProgressBar = (ProgressBar) v.findViewById(R.id.progressBar);
        return v;
    }

    @Override
    public void setUserVisibleHint(boolean visible) {
        super.setUserVisibleHint(visible);

        if (visible && isResumed()) {
            // Only manually call onResume if fragment is already visible
            // Otherwise allow natural fragment lifecycle to call onResume
            onResume();
        }
    }

    @Override
    public void onResume() {
        super.onResume();

        if (!getUserVisibleHint()) {
            // Do nothing for now
        }
        // 4th parameter is null, because playlist are not needed to this fragment

        youTubeSearch = new YouTubeSearch(getActivity(), this);
        youTubeSearch.setYouTubeVideosReceiver(this);
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        videosFoundListView = (DynamicListView) getListView();
        setupListViewAndAdapter();
        addListeners();
    }

    /**
     * Setups custom adapter which enables animations when adding elements
     */
    private void setupListViewAndAdapter() {
        videoListAdapter = new VideosAdapter(getActivity(), searchResultsList, false);
        SwingBottomInAnimationAdapter animationAdapter = new SwingBottomInAnimationAdapter(videoListAdapter);
        animationAdapter.setAbsListView(videosFoundListView);
        videosFoundListView.setAdapter(animationAdapter);
    }

    /**
     * Search for query on youTube by using YouTube Data API V3
     *
     * @param query
     */
    public void searchQuery(String query) {
        //check network connectivity
        if (!networkConf.isNetworkAvailable()) {
            networkConf.createNetErrorDialog();
            return;
        }

        loadingProgressBar.setVisibility(View.VISIBLE);
        onScrollIndex = 0;
        youTubeSearch.searchVideos(query);
    }

    /**
     * Adds listener for item list selection and starts BackgroundAudioService
     */
    private void addListeners() {

        videosFoundListView.setOnItemClickListener(new AdapterView.OnItemClickListener() {

            @Override
            public void onItemClick(AdapterView<?> av, View v, int pos,
                                    long id) {
                // Check network connectivity
                if (!networkConf.isNetworkAvailable()) {
                    networkConf.createNetErrorDialog();
                    return;
                }

                Toast.makeText(
                        getContext(),
                        getResources().getString(R.string.toast_message_playing) + searchResultsList.get(pos).getTitle(),
                        Toast.LENGTH_SHORT
                ).show();

                YouTubeSqlDb.getInstance().videos(YouTubeSqlDb.VIDEOS_TYPE.RECENTLY_WATCHED).create(searchResultsList.get(pos));

                Intent serviceIntent = new Intent(getContext(), BackgroundAudioService.class);
                serviceIntent.setAction(BackgroundAudioService.ACTION_PLAY);
                serviceIntent.putExtra(Config.YOUTUBE_TYPE, Config.YOUTUBE_MEDIA_TYPE_VIDEO);
                serviceIntent.putExtra(Config.YOUTUBE_TYPE_VIDEO, searchResultsList.get(pos));
                getActivity().startService(serviceIntent);
            }
        });

        videosFoundListView.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(AbsListView view, int scrollState) {

            }

            @Override
            public void onScroll(AbsListView view, int firstVisibleItem, int visibleItemCount, int totalItemCount) {

                //if specified number of videos is added, do not load more
                if (totalItemCount < Config.NUMBER_OF_VIDEOS_RETURNED) {
                    if (view.getAdapter() != null &&
                            ((firstVisibleItem + visibleItemCount) >= totalItemCount) &&
                            totalItemCount != mPrevTotalItemCount) {
                        mPrevTotalItemCount = totalItemCount;
                        addMoreData();
                    }
                }
            }
        });
    }

    /**
     * Called when video items are received
     *
     * @param youTubeVideos - videos to be shown in list view
     */
    @Override
    public void onVideosReceived(ArrayList<YouTubeVideo> youTubeVideos) {

        videosFoundListView.smoothScrollToPosition(0);
        searchResultsList.clear();
        scrollResultsList.clear();
        scrollResultsList.addAll(youTubeVideos);

        handler.post(new Runnable() {
            public void run() {
                loadingProgressBar.setVisibility(View.INVISIBLE);
            }
        });

        addMoreData();
    }

    /**
     * Called when playlist cannot be found
     * NOT USED in this fragment
     *
     * @param playlistId
     * @param errorCode
     */
    @Override
    public void onPlaylistNotFound(String playlistId, int errorCode) {

    }

    /**
     * Adds 10 items at the bottom of the list when list is scrolled to the end (10th element)
     * 50 is max number of videos
     * If number is between, so no full step is available (step is 10), add elements:
     * scrollResultsList.size() % 10
     */
    private void addMoreData() {

        List<YouTubeVideo> subList;
        if (scrollResultsList.size() < (onScrollIndex + 10)) {
            subList = scrollResultsList.subList(onScrollIndex, scrollResultsList.size());
            onScrollIndex += (scrollResultsList.size() % 10);
        } else {
            subList = scrollResultsList.subList(onScrollIndex, onScrollIndex + 10);
            onScrollIndex += 10;
        }

        if (!subList.isEmpty()) {
            searchResultsList.addAll(subList);
            handler.post(new Runnable() {
                public void run() {
                    if (videoListAdapter != null) {
                        videoListAdapter.notifyDataSetChanged();
                    }
                }
            });
        }
    }
}

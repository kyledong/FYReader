package xyz.fycz.myreader.ui.presenter;

import android.annotation.SuppressLint;
import android.app.Dialog;
import android.app.Notification;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Handler;
import android.os.Message;
import android.view.View;
import android.view.WindowManager;
import android.widget.SeekBar;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import xyz.fycz.myreader.R;
import xyz.fycz.myreader.application.MyApplication;
import xyz.fycz.myreader.application.SysManager;
import xyz.fycz.myreader.base.BasePresenter;
import xyz.fycz.myreader.webapi.callback.ResultCallback;
import xyz.fycz.myreader.common.APPCONST;
import xyz.fycz.myreader.common.URLCONST;
import xyz.fycz.myreader.ui.dialog.MyAlertDialog;
import xyz.fycz.myreader.ui.dialog.SourceExchangeDialog;
import xyz.fycz.myreader.ui.activity.*;
import xyz.fycz.myreader.util.*;
import xyz.fycz.myreader.util.utils.ColorUtil;
import xyz.fycz.myreader.webapi.crawler.base.ReadCrawler;
import xyz.fycz.myreader.webapi.crawler.ReadCrawlerUtil;
import xyz.fycz.myreader.ui.dialog.DialogCreator;
import xyz.fycz.myreader.entity.Setting;
import xyz.fycz.myreader.enums.BookSource;
import xyz.fycz.myreader.enums.Font;
import xyz.fycz.myreader.enums.ReadStyle;
import xyz.fycz.myreader.greendao.entity.Book;
import xyz.fycz.myreader.greendao.entity.BookMark;
import xyz.fycz.myreader.greendao.entity.Chapter;
import xyz.fycz.myreader.greendao.service.BookMarkService;
import xyz.fycz.myreader.greendao.service.BookService;
import xyz.fycz.myreader.greendao.service.ChapterService;
import xyz.fycz.myreader.util.notification.NotificationClickReceiver;
import xyz.fycz.myreader.util.notification.NotificationUtil;
import xyz.fycz.myreader.util.utils.NetworkUtils;
import xyz.fycz.myreader.webapi.CommonApi;
import xyz.fycz.myreader.widget.page.LocalPageLoader;
import xyz.fycz.myreader.widget.page.PageLoader;
import xyz.fycz.myreader.widget.page.PageMode;
import xyz.fycz.myreader.widget.page.PageView;

import static androidx.appcompat.app.AppCompatActivity.RESULT_OK;
import static xyz.fycz.myreader.util.UriFileUtil.getPath;


public class ReadPresenter implements BasePresenter {


    private ReadActivity mReadActivity;
    private Book mBook;
    private ArrayList<Chapter> mChapters = new ArrayList<>();
    private ChapterService mChapterService;
    private BookService mBookService;
    private BookMarkService mBookMarkService;
    private NotificationUtil notificationUtil;
    private Setting mSetting;

    private boolean settingChange;//是否是设置改变

    private boolean chapterChange;//章节是否改变

    private boolean isCollected = true;//是否在书架中

    private boolean isPrev;//是否向前翻页

    private boolean autoPage = false;//是否自动翻页

    private Dialog mSettingDialog;//设置视图
    private Dialog mSettingDetailDialog;//详细设置视图
    private Dialog mPageModeDialog;//翻页模式实体

    private int curCacheChapterNum = 0;//缓存章节数

    private int needCacheChapterNum;//需要缓存的章节

    private PageLoader mPageLoader;//页面加载器

    private int screenTimeOut;//息屏时间（单位：秒），dengy零表示常亮

    private Runnable keepScreenRunnable;//息屏线程
    private Runnable autoPageRunnable;//自动翻页
    private Runnable upHpbNextPage;//更新自动翻页进度条
    private Runnable sendDownloadNotification;
    private static boolean isStopDownload = true;

    private int tempCacheChapterNum;
    private int tempCount;
    private String downloadingChapter;

    private ReadCrawler mReadCrawler;

    private int endPageTipCount = 3;

    private int nextPageTime;//下次翻页时间

    private int upHpbInterval = 30;//更新翻页进度速度

    private int downloadInterval = 150;

    private final CharSequence[] pageMode = {
            "覆盖", "仿真", "滑动", "滚动", "无动画"
    };

    private SourceExchangeDialog mSourceDialog;

    private boolean hasChangeSource;

    @SuppressLint("HandlerLeak")
    private Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case 1:
                    init();
                    break;
                case 2:
                    int chapterPos = msg.arg1;
                    int pagePos = msg.arg2;
                    mPageLoader.skipToChapter(chapterPos);
                    mPageLoader.skipToPage(pagePos);
                    mReadActivity.getPbLoading().setVisibility(View.GONE);
                    break;
                case 3:
                    updateDownloadProgress((TextView) msg.obj);
                    break;
                case 4:
                    saveLastChapterReadPosition();
                    screenOffTimerStart();
                    break;
                case 5:
                    if (endPageTipCount > 0) {
                        endPageTipCount--;
                        mHandler.sendEmptyMessageDelayed(5, 1000);
                    } else {
                        mReadActivity.getTvEndPageTip().setVisibility(View.GONE);
                        endPageTipCount = 3;
                    }
                    break;
                case 6:
                    mPageLoader.openChapter();
                    if (isPrev) {//判断是否向前翻页打开章节，如果是则打开自己后跳转到最后一页，否则不跳转
                        try {//概率性异常（空指针异常）
                            mPageLoader.skipToPage(mPageLoader.getAllPagePos() - 1);
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }
                    break;
                case 7:
                    ToastUtils.showWarring("无网络连接！");
                    mPageLoader.chapterError();
                    break;
                case 8:
                    mReadActivity.getPbLoading().setVisibility(View.GONE);
                    break;
                case 9:
                    ToastUtils.showInfo("正在后台缓存书籍，具体进度可查看通知栏！");
                    notificationUtil.requestNotificationPermissionDialog(mReadActivity);
                    break;
                case 10:
                    if (mPageLoader != null) {
                        mPageLoader.chapterError();
                    }
            }
        }
    };

    // 接收电池信息和时间更新的广播
    private BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_BATTERY_CHANGED.equals(intent.getAction())) {
                int level = intent.getIntExtra("level", 0);
                try {
                    mPageLoader.updateBattery(level);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
            // 监听分钟的变化
            else if (Intent.ACTION_TIME_TICK.equals(intent.getAction())) {
                try {
                    mPageLoader.updateTime();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
    };


    public ReadPresenter(ReadActivity readActivity) {
        mReadActivity = readActivity;
        mBookService = BookService.getInstance();
        mChapterService = ChapterService.getInstance();
        mBookMarkService = BookMarkService.getInstance();
        mSetting = SysManager.getSetting();
    }


    @Override
    public void start() {
        if (SharedPreUtils.getInstance().getBoolean("isNightFS", false)) {
            mSetting.setDayStyle(!ColorUtil.isColorLight(mReadActivity.getColor(R.color.textPrimary)));
        }

        //息屏时间
        screenTimeOut = mSetting.getResetScreen() * 60;

        //保持屏幕常亮
        keepScreenRunnable = this::unKeepScreenOn;

        autoPageRunnable = this::nextPage;

        upHpbNextPage = this::upHpbNextPage;

        sendDownloadNotification = this::sendNotification;

        notificationUtil = NotificationUtil.getInstance();

        //注册广播
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(Intent.ACTION_BATTERY_CHANGED);
        intentFilter.addAction(Intent.ACTION_TIME_TICK);
        mReadActivity.registerReceiver(mReceiver, intentFilter);

        if (!mSetting.isBrightFollowSystem()) {
            BrightUtil.setBrightness(mReadActivity, BrightUtil.progressToBright(mSetting.getBrightProgress()));
        }

        if (!loadBook()) {
            mReadActivity.finish();
            return;
        }

        isCollected = mReadActivity.getIntent().getBooleanExtra("isCollected", true);

        hasChangeSource = mReadActivity.getIntent().getBooleanExtra("hasChangeSource", false);

        //当书籍Collected且书籍id不为空的时候保存上次阅读信息
        if (isCollected && !StringHelper.isEmpty(mBook.getId())) {
            //保存上次阅读信息
            SharedPreUtils.getInstance().putString("lastRead", mBook.getId());
        }


        mReadCrawler = ReadCrawlerUtil.getReadCrawler(mBook.getSource());

        mPageLoader = mReadActivity.getSrlContent().getPageLoader(mBook, mReadCrawler, mSetting);

        mReadActivity.getPbLoading().setVisibility(View.VISIBLE);

        initListener();
        //Dialog
        mSourceDialog = new SourceExchangeDialog(mReadActivity, mBook);

        mSourceDialog.setOnSourceChangeListener((bean, pos) -> {
            Book bookTem = new Book(mBook);
            bookTem.setChapterUrl(bean.getChapterUrl());
            bookTem.setSource(bean.getSource());
            if (!StringHelper.isEmpty(bean.getImgUrl())) {
                bookTem.setImgUrl(bean.getImgUrl());
            }
            if (!StringHelper.isEmpty(bean.getType())) {
                bookTem.setType(bean.getType());
            }
            if (!StringHelper.isEmpty(bean.getDesc())){
                bookTem.setDesc(bean.getDesc());
            }
            if (isCollected) {
                mBookService.updateBook(mBook, bookTem);
            }
            mBook = bookTem;
            mSettingDialog.dismiss();
            Intent intent = new Intent(mReadActivity, ReadActivity.class)
                    .putExtra(APPCONST.BOOK, mBook)
                    .putExtra("hasChangeSource", true);
            if (!isCollected){
                intent.putExtra("isCollected", false);
            }
            mReadActivity.finish();
            mReadActivity.startActivity(intent);
        });
        getData();
    }

    /**
     * 进入阅读书籍有三种方式：
     * 1、直接从书架进入，这种方式书籍一定Collected
     * 2、从外部打开txt文件，这种方式会添加进书架
     * 3、从快捷图标打开上次阅读书籍
     *
     * @return 是否加载成功
     */
    private boolean loadBook() {
        //是否直接打开本地txt文件
        String path = null;
        if (Intent.ACTION_VIEW.equals(mReadActivity.getIntent().getAction())) {
            Uri uri = mReadActivity.getIntent().getData();
            if (uri != null) {
                path = getPath(mReadActivity, uri);
            }
        }
        if (!StringHelper.isEmpty(path)) {
            //本地txt文件路径不为空，添加书籍
            addLocalBook(path);
        } else {
            //路径为空，说明不是直接打开txt文件
            mBook = (Book) mReadActivity.getIntent().getSerializableExtra(APPCONST.BOOK);
            //mBook为空，说明是从快捷方式启动
            if (mBook == null) {
                String bookId = SharedPreUtils.getInstance().getString("lastRead", "");
                if ("".equals(bookId)) {//没有上次阅读信息
                    ToastUtils.showWarring("当前没有阅读任何书籍，无法加载上次阅读书籍！");
                    mReadActivity.finish();
                    return false;
                } else {//有信息
                    mBook = mBookService.getBookById(bookId);
                    if (mBook == null) {//上次阅读的书籍不存在
                        ToastUtils.showWarring("上次阅读书籍已不存在/移除书架，无法加载！");
                        mReadActivity.finish();
                        return false;
                    }//存在就继续执行
                }
            }
        }
        return true;
    }

    /**
     * 保存最后阅读章节的进度
     */
    public void saveLastChapterReadPosition() {
        if (!StringHelper.isEmpty(mBook.getId()) && mPageLoader.getPageStatus() == PageLoader.STATUS_FINISH) {
            mBook.setLastReadPosition(mPageLoader.getPagePos());
            mBook.setHisttoryChapterNum(mPageLoader.getChapterPos());
            mBookService.updateEntity(mBook);
        }
    }

    /**
     * 创建设置视图
     */
    private void createSettingView() {
        mSettingDialog = DialogCreator.createReadSetting(mReadActivity, mSetting.isDayStyle(),
                mPageLoader.getPagePos(), Math.max(0, mPageLoader.getAllPagePos() - 1),
                mBook, mChapters.get(mPageLoader.getChapterPos()),
                view -> {//返回
                    mSettingDialog.dismiss();
                    mReadActivity.onBackPressed();
                }, v -> {//换源
                    mSourceDialog.show();
                }, v -> {//刷新
                    isPrev = false;
                    if (!"本地书籍".equals(mBook.getType())) {
                        mChapterService.deleteChapterCacheFile(mChapters.get(mPageLoader.getChapterPos()));
                    }
                    mPageLoader.refreshChapter(mChapters.get(mPageLoader.getChapterPos()));
                }, v -> {//添加书签
                    Chapter curChapter = mChapters.get(mPageLoader.getChapterPos());
                    BookMark bookMark = new BookMark();
                    bookMark.setBookId(mBook.getId());
                    bookMark.setTitle(curChapter.getTitle());
                    bookMark.setBookMarkChapterNum(mPageLoader.getChapterPos());
                    bookMark.setBookMarkReadPosition(mPageLoader.getPagePos());
                    mBookMarkService.addOrUpdateBookMark(bookMark);
                    DialogCreator.createTipDialog(mReadActivity, "《" + mBook.getName() +
                            "》：" + bookMark.getTitle() + "[" + (bookMark.getBookMarkReadPosition() + 1) +
                            "]\n书签添加成功，书签列表可在目录界面查看！");
                }, (chapterTitle, chapterUrl, sbReadChapterProgress) -> {//上一章
                    isPrev = false;
                    mPageLoader.skipPreChapter();
                    String url = mChapters.get(mPageLoader.getChapterPos()).getUrl();
                    if ("null".equals(mBook.getSource()) || BookSource.fynovel.equals(mBook.getSource())) {
                        if (!url.contains("novel.fycz.xyz")) {
                            url = URLCONST.nameSpace_FY + url;
                        }
                    }
                    chapterTitle.setText(mChapters.get(mPageLoader.getChapterPos()).getTitle());
                    chapterUrl.setText(StringHelper.isEmpty(url) ? mChapters.
                            get(mPageLoader.getChapterPos()).getId() : url);
                    sbReadChapterProgress.setProgress(0);
                    sbReadChapterProgress.setMax(Math.max(0, mPageLoader.getAllPagePos() - 1));
                    chapterChange = false;
                }, (chapterTitle, chapterUrl, sbReadChapterProgress) -> {//下一章
                    isPrev = false;
                    mPageLoader.skipNextChapter();
                    String url = mChapters.get(mPageLoader.getChapterPos()).getUrl();
                    if ("null".equals(mBook.getSource()) || BookSource.fynovel.equals(mBook.getSource())) {
                        if (!url.contains("novel.fycz.xyz")) {
                            url = URLCONST.nameSpace_FY + url;
                        }
                    }
                    chapterTitle.setText(mChapters.get(mPageLoader.getChapterPos()).getTitle());
                    chapterUrl.setText(StringHelper.isEmpty(url) ? mChapters.
                            get(mPageLoader.getChapterPos()).getId() : url);
                    sbReadChapterProgress.setProgress(0);
                    sbReadChapterProgress.setMax(Math.max(0, mPageLoader.getAllPagePos() - 1));
                    chapterChange = false;
                }, view -> {//目录
                    /*initChapterTitleList();
                    mReadActivity.getDlReadActivity().openDrawer(GravityCompat.START);*/
                    mSettingDialog.dismiss();
                    Intent intent = new Intent(mReadActivity, CatalogActivity.class);
                    intent.putExtra(APPCONST.BOOK, mBook);
                    mReadActivity.startActivityForResult(intent, APPCONST.REQUEST_CHAPTER_PAGE);
                }, (dialog, view, isDayStyle) -> {//日夜切换
                    dialog.dismiss();
                    changeNightAndDaySetting(isDayStyle);
                }, view -> {//设置
                    showSettingDetailView();
                }, new SeekBar.OnSeekBarChangeListener() {//阅读进度
                    @Override
                    public void onProgressChanged(SeekBar seekBar, int i, boolean b) {

                    }

                    @Override
                    public void onStartTrackingTouch(SeekBar seekBar) {

                    }

                    @Override
                    public void onStopTrackingTouch(SeekBar seekBar) {
                        //进行切换
                        int pagePos = seekBar.getProgress();
                        if (pagePos != mPageLoader.getPagePos() && pagePos < mPageLoader.getAllPagePos()) {
                            mPageLoader.skipToPage(pagePos);
                        }
                    }
                }
                , null, (dialog, view, tvDownloadProgress) -> {
                    if (!isCollected) {
                        addBookToCaseAndDownload(tvDownloadProgress);
                    } else {
                        downloadBook(tvDownloadProgress);
                    }

                });
    }

    /**
     * 显示设置视图
     */
    private void showSettingView() {
        if (mSettingDialog != null && !chapterChange) {
            mSettingDialog.show();
        } else {
            if (mPageLoader.getPageStatus() == PageLoader.STATUS_FINISH) {
                createSettingView();
                chapterChange = false;
            }
            mSettingDialog.show();
        }

    }

    /**
     * 添加到书架并缓存书籍
     *
     * @param tvDownloadProgress
     */
    private void addBookToCaseAndDownload(final TextView tvDownloadProgress) {
        DialogCreator.createCommonDialog(mReadActivity, mReadActivity.getString(R.string.tip), mReadActivity.getString(R.string.download_no_add_tips), true, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                downloadBook(tvDownloadProgress);
                isCollected = true;
            }
        }, (dialog, which) -> dialog.dismiss());
    }

    /**
     * 创建详细设置视图
     */
    private void createSettingDetailView() {
        mSettingDetailDialog = DialogCreator.createReadDetailSetting(mReadActivity, mSetting,
                this::changeStyle, v -> reduceTextSize(), v -> increaseTextSize(), v -> {
                    if (mSetting.isVolumeTurnPage()) {
                        mSetting.setVolumeTurnPage(false);
                        ToastUtils.showSuccess("音量键翻页已关闭！");
                    } else {
                        mSetting.setVolumeTurnPage(true);
                        ToastUtils.showSuccess("音量键翻页已开启！");
                    }
                    SysManager.saveSetting(mSetting);
                }, v -> {
                    Intent intent = new Intent(mReadActivity, FontsActivity.class);
                    mReadActivity.startActivityForResult(intent, APPCONST.REQUEST_FONT);
                    mSettingDetailDialog.dismiss();
                }, this::showPageModeDialog, v -> {
                    if (mSetting.getPageMode() == PageMode.SCROLL) {
                        ToastUtils.showWarring("滚动暂时不支持自动翻页");
                        return;
                    }
                    mSettingDetailDialog.dismiss();
                    autoPage = !autoPage;
                    autoPage();
                }, v -> {
                    Intent intent = new Intent(mReadActivity, MoreSettingActivity.class);
                    mReadActivity.startActivityForResult(intent, APPCONST.REQUEST_RESET_SCREEN_TIME);
                    mSettingDetailDialog.dismiss();
                });
    }

    /**
     * 显示详细设置视图
     */
    private void showSettingDetailView() {
        mSettingDialog.dismiss();
        if (mSettingDetailDialog != null) {
            mSettingDetailDialog.show();
        } else {
            createSettingDetailView();
            mSettingDetailDialog.show();
        }
    }

    private void showPageModeDialog(final TextView tvPageMode) {
        if (mPageModeDialog != null) {
            mPageModeDialog.show();
        } else {
            //显示翻页模式视图
            int checkedItem;
            switch (mSetting.getPageMode()) {
                case COVER:
                    checkedItem = 0;
                    break;
                case SIMULATION:
                    checkedItem = 1;
                    break;
                case SLIDE:
                    checkedItem = 2;
                    break;
                case SCROLL:
                    checkedItem = 3;
                    break;
                case NONE:
                    checkedItem = 4;
                    break;
                default:
                    checkedItem = 0;
            }
            mPageModeDialog = MyAlertDialog.build(mReadActivity)
                    .setTitle("翻页模式")
                    .setSingleChoiceItems(pageMode, checkedItem, (dialog, which) -> {
                        switch (which) {
                            case 0:
                                mSetting.setPageMode(PageMode.COVER);
                                break;
                            case 1:
                                mSetting.setPageMode(PageMode.SIMULATION);
                                break;
                            case 2:
                                mSetting.setPageMode(PageMode.SLIDE);
                                break;
                            case 3:
                                mSetting.setPageMode(PageMode.SCROLL);
                                break;
                            case 4:
                                mSetting.setPageMode(PageMode.NONE);
                                break;
                        }
                        mPageModeDialog.dismiss();
                        SysManager.saveSetting(mSetting);
                        MyApplication.runOnUiThread(() -> mPageLoader.setPageMode(mSetting.getPageMode()));
                        tvPageMode.setText(pageMode[which]);
                    }).show();
        }
    }

    /**
     * 字体结果回调
     *
     * @param requestCode
     * @param resultCode
     * @param data
     */
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (resultCode == RESULT_OK) {
            switch (requestCode) {
                case APPCONST.REQUEST_FONT:
                    Font font = (Font) data.getSerializableExtra(APPCONST.FONT);
                    mSetting.setFont(font);
                    settingChange = true;
//                    init();
                    MyApplication.runOnUiThread(() -> mPageLoader.setFont(font));
                    break;
                case APPCONST.REQUEST_CHAPTER_PAGE:
                    int[] chapterAndPage = data.getIntArrayExtra(APPCONST.CHAPTER_PAGE);
                    assert chapterAndPage != null;
                    skipToChapterAndPage(chapterAndPage[0], chapterAndPage[1]);
                    break;
                case APPCONST.REQUEST_RESET_SCREEN_TIME:
                    int resetScreen = data.getIntExtra(APPCONST.RESULT_RESET_SCREEN, 0);
                    screenTimeOut = resetScreen * 60;
                    screenOffTimerStart();
                    break;
            }
        }
    }


    /**
     * 初始化
     */
    private void init() {
        screenOffTimerStart();
        mPageLoader.init();
        mPageLoader.refreshChapterList();
        mHandler.sendMessage(mHandler.obtainMessage(8));
    }

    /**
     * 初始化监听器
     */
    private void initListener() {

        mReadActivity.getSrlContent().setTouchListener(new PageView.TouchListener() {
            @Override
            public boolean onTouch() {
                screenOffTimerStart();
                return true;
            }

            @Override
            public void center() {
                if (mPageLoader.getPageStatus() == PageLoader.STATUS_FINISH) {
                    showSettingView();
                }
                if (autoPage) {
                    autoPageStop();
                }
            }

            @Override
            public void prePage() {
                isPrev = true;
            }

            @Override
            public void nextPage(boolean hasNextPage) {
                isPrev = false;
                if (!hasNextPage && endPageTipCount == 3) {
                    mReadActivity.getTvEndPageTip().setVisibility(View.VISIBLE);
                    mHandler.sendMessage(mHandler.obtainMessage(5));
                    if (autoPage) {
                        autoPageStop();
                    }
                }
            }

            @Override
            public void cancel() {
            }
        });

        mPageLoader.setOnPageChangeListener(
                new PageLoader.OnPageChangeListener() {
                    @Override
                    public void onChapterChange(int pos) {
                        chapterChange = true;
                        lastLoad(pos);
                        for (int i = 0; i < 5; i++) {
                            preLoad(pos - 1 + i);
                        }
                        mBook.setHistoryChapterId(mChapters.get(pos).getTitle());
                        mHandler.sendMessage(mHandler.obtainMessage(4));
                        MyApplication.getApplication().newThread(() -> {
                            if (mPageLoader.getPageStatus() == PageLoader.STATUS_LOADING) {
                                if (!NetworkUtils.isNetWorkAvailable()) {
                                    mHandler.sendMessage(mHandler.obtainMessage(7));
                                } else {
                                    mHandler.sendMessage(mHandler.obtainMessage(6));
                                }
                            }
                        });

                    }

                    @Override
                    public void requestChapters(List<Chapter> requestChapters) {
                        /*for (final Chapter chapter : requestChapters){
                            getChapterContent(chapter, null);
                        }*/
                    }

                    @Override
                    public void onCategoryFinish(List<Chapter> chapters) {
                    }

                    @Override
                    public void onPageCountChange(int count) {

                    }

                    @Override
                    public void onPageChange(int pos) {
                        mHandler.sendMessage(mHandler.obtainMessage(4));
                    }

                    @Override
                    public void preLoading() {

                    }
                }
        );

    }


    /**
     * 章节数据网络同步
     */
    private void getData() {
        mChapters = (ArrayList<Chapter>) mChapterService.findBookAllChapterByBookId(mBook.getId());
        if (!isCollected || mChapters.size() == 0 || ("本地书籍".equals(mBook.getType()) &&
                !ChapterService.isChapterCached(mBook.getId(), mChapters.get(0).getTitle()))) {
            if ("本地书籍".equals(mBook.getType())) {
                if (!new File(mBook.getChapterUrl()).exists()) {
                    ToastUtils.showWarring("书籍缓存为空且源文件不存在，书籍加载失败！");
                    mReadActivity.finish();
                    return;
                }
                ((LocalPageLoader) mPageLoader).loadChapters(new ResultCallback() {
                    @Override
                    public void onFinish(Object o, int code) {
                        ArrayList<Chapter> chapters = (ArrayList<Chapter>) o;
                        mBook.setChapterTotalNum(chapters.size());
                        mBook.setNewestChapterTitle(chapters.get(chapters.size() - 1).getTitle());
                        mBookService.updateEntity(mBook);
                        if (mChapters.size() == 0) {
                            updateAllOldChapterData(chapters);
                        }
                        initChapters();
                        mHandler.sendMessage(mHandler.obtainMessage(1));
                    }

                    @Override
                    public void onError(Exception e) {
                        mChapters.clear();
                        initChapters();
                        mHandler.sendMessage(mHandler.obtainMessage(1));
                    }
                });
            } else {
                CommonApi.getBookChapters(mBook.getChapterUrl(), mReadCrawler,false, new ResultCallback() {
                    @Override
                    public void onFinish(Object o, int code) {
                        ArrayList<Chapter> chapters = (ArrayList<Chapter>) o;
                        updateAllOldChapterData(chapters);
                        initChapters();
                    }

                    @Override
                    public void onError(Exception e) {
//                settingChange = true;
                        initChapters();
                        mHandler.sendMessage(mHandler.obtainMessage(1));
                    }
                });
            }
        } else {
            initChapters();
        }
    }

    /**
     * 初始化章节
     */
    private void initChapters() {
        mBook.setNoReadNum(0);
        mBook.setChapterTotalNum(mChapters.size());
        if (!StringHelper.isEmpty(mBook.getId())) {
            mBookService.updateEntity(mBook);
        }
        if (mChapters.size() == 0) {
            ToastUtils.showWarring("该书查询不到任何章节");
            mHandler.sendMessage(mHandler.obtainMessage(8));
            settingChange = false;
        } else {
            if (mBook.getHisttoryChapterNum() < 0) {
                mBook.setHisttoryChapterNum(0);
            } else if (mBook.getHisttoryChapterNum() >= mChapters.size()) {
                mBook.setHisttoryChapterNum(mChapters.size() - 1);
            }
            if ("本地书籍".equals(mBook.getType())) {
                mHandler.sendMessage(mHandler.obtainMessage(1));
                return;
            }
            if (hasChangeSource){
                mBookService.matchHistoryChapterPos(mBook, mChapters);
            }
            getChapterContent(mChapters.get(mBook.getHisttoryChapterNum()), new ResultCallback() {
                @Override
                public void onFinish(Object o, int code) {
//                            mChapters.get(mBook.getHisttoryChapterNum()).setContent((String) o);
                    mChapterService.saveOrUpdateChapter(mChapters.get(mBook.getHisttoryChapterNum()), (String) o);
                    mHandler.sendMessage(mHandler.obtainMessage(1));
//                        getAllChapterData();
                }

                @Override
                public void onError(Exception e) {
                    mHandler.sendMessage(mHandler.obtainMessage(1));
                }
            });
        }
    }

    /**
     * 更新所有章节
     *
     * @param newChapters
     */
    private void updateAllOldChapterData(ArrayList<Chapter> newChapters) {
        for (Chapter newChapter : newChapters) {
            newChapter.setId(StringHelper.getStringRandom(25));
            newChapter.setBookId(mBook.getId());
            mChapters.add(newChapter);
//                mChapterService.addChapter(newChapters.get(j));
        }
        mChapterService.addChapters(mChapters);
    }


    /****************************************** 缓存章节***************************************************************/
    private int selectedIndex;//对话框选择下标

    protected void downloadBook(final TextView tvDownloadProgress) {
        if ("本地书籍".equals(mBook.getType())) {
            ToastUtils.showWarring("《" + mBook.getName() + "》是本地书籍，不能缓存");
            return;
        }
        if (!NetworkUtils.isNetWorkAvailable()) {
            ToastUtils.showWarring("无网络连接！");
            return;
        }
        MyApplication.runOnUiThread(() -> {
            MyAlertDialog.build(mReadActivity)
                    .setTitle("缓存书籍")
                    .setSingleChoiceItems(APPCONST.DIALOG_DOWNLOAD, selectedIndex, (dialog, which) -> selectedIndex = which).setNegativeButton("取消", ((dialog, which) -> dialog.dismiss())).setPositiveButton("确定",
                    (dialog, which) -> {
                        switch (selectedIndex) {
                            case 0:
                                addDownload(tvDownloadProgress, mPageLoader.getChapterPos(), mPageLoader.getChapterPos() + 50);
                                break;
                            case 1:
                                addDownload(tvDownloadProgress, mPageLoader.getChapterPos() - 50, mPageLoader.getChapterPos() + 50);
                                break;
                            case 2:
                                addDownload(tvDownloadProgress, mPageLoader.getChapterPos(), mChapters.size());
                                break;
                            case 3:
                                addDownload(tvDownloadProgress, 0, mChapters.size());
                                break;
                        }
                    }).show();
        });
    }

    private void addDownload(final TextView tvDownloadProgress, int begin, int end) {
        /*//取消之前下载
        if (!isStopDownload) {
            isStopDownload = true;
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }*/
        if (SysManager.getSetting().getCatheGap() != 0) {
            downloadInterval = SysManager.getSetting().getCatheGap();
        }
        //计算断点章节
        final int finalBegin = Math.max(0, begin);
        final int finalEnd = Math.min(end, mChapters.size());
        needCacheChapterNum = finalEnd - finalBegin;
        curCacheChapterNum = 0;
        isStopDownload = false;
        ArrayList<Chapter> needDownloadChapters = new ArrayList<>();
        for (int i = finalBegin; i < finalEnd; i++) {
            final Chapter chapter = mChapters.get(i);
            if (StringHelper.isEmpty(chapter.getContent())) {
                needDownloadChapters.add(chapter);
            }
        }
        needCacheChapterNum = needDownloadChapters.size();
        if (needCacheChapterNum > 0) {
            mHandler.sendEmptyMessage(9);
            mHandler.postDelayed(sendDownloadNotification, 2 * downloadInterval);
        }
        MyApplication.getApplication().newThread(() -> {
            for (Chapter chapter : needDownloadChapters) {
                getChapterContent(chapter, new ResultCallback() {
                    @Override
                    public void onFinish(Object o, int code) {
                        downloadingChapter = chapter.getTitle();
                        mChapterService.saveOrUpdateChapter(chapter, (String) o);
                        curCacheChapterNum++;
                        mHandler.sendMessage(mHandler.obtainMessage(3, tvDownloadProgress));
                    }

                    @Override
                    public void onError(Exception e) {
                        curCacheChapterNum++;
                        mHandler.sendMessage(mHandler.obtainMessage(3, tvDownloadProgress));
                    }
                });
                try {
                    Thread.sleep(downloadInterval);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                if (isStopDownload) {
                    break;
                }
            }
            if (curCacheChapterNum == needCacheChapterNum) {
                ToastUtils.showInfo("《" + mBook.getName() + "》" + mReadActivity.getString(R.string.download_already_all_tips));
            }
        });
    }


    private void updateDownloadProgress(TextView tvDownloadProgress) {
        try {
            tvDownloadProgress.setText(curCacheChapterNum * 100 / needCacheChapterNum + " %");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * 发送通知
     */
    private void sendNotification() {
        if (curCacheChapterNum == needCacheChapterNum) {
            notificationUtil.cancel(1001);
            return;
        } else {
            Notification notification = notificationUtil.build(APPCONST.channelIdDownload)
                    .setSmallIcon(R.drawable.ic_download)
                    //通知栏大图标
                    .setLargeIcon(BitmapFactory.decodeResource(MyApplication.getApplication().getResources(), R.mipmap.ic_launcher))
                    .setOngoing(true)
                    //点击通知后自动清除
                    .setAutoCancel(true)
                    .setContentTitle("正在下载：" + mBook.getName() +
                            "[" + curCacheChapterNum + "/" + needCacheChapterNum + "]")
                    .setContentText(downloadingChapter == null ? "  " : downloadingChapter)
                    .addAction(R.drawable.ic_stop_black_24dp, "停止",
                            notificationUtil.getChancelPendingIntent(cancelDownloadReceiver.class))
                    .build();
            notificationUtil.notify(1001, notification);
        }
        if (tempCacheChapterNum < curCacheChapterNum) {
            tempCount = 1500 / downloadInterval;
            tempCacheChapterNum = curCacheChapterNum;
        } else if (tempCacheChapterNum == curCacheChapterNum) {
            tempCount--;
            if (tempCount == 0) {
                notificationUtil.cancel(1001);
                return;
            }
        }
        mHandler.postDelayed(sendDownloadNotification, 2 * downloadInterval);
    }

    public static class cancelDownloadReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            //todo 跳转之前要处理的逻辑
            if (NotificationClickReceiver.CANCEL_ACTION.equals(intent.getAction())) {
                isStopDownload = true;
            }
        }
    }

    /**
     * 获取章节内容
     *
     * @param chapter
     * @param resultCallback
     */
    private void getChapterContent(final Chapter chapter, ResultCallback resultCallback) {
        if (StringHelper.isEmpty(chapter.getBookId())) {
            chapter.setId(mBook.getId());
        }
        if (!StringHelper.isEmpty(chapter.getContent())) {
            if (resultCallback != null) {
                resultCallback.onFinish(mChapterService.getChapterCatheContent(chapter), 0);
            }
        } else {
            if ("本地书籍".equals(mBook.getType())) {
                return;
            }
            if (resultCallback != null) {
                CommonApi.getChapterContent(chapter.getUrl(), mReadCrawler, resultCallback);
            } else {
                CommonApi.getChapterContent(chapter.getUrl(), mReadCrawler, new ResultCallback() {
                    @Override
                    public void onFinish(final Object o, int code) {
//                        chapter.setContent((String) o);
                        mChapterService.saveOrUpdateChapter(chapter, (String) o);
                    }

                    @Override
                    public void onError(Exception e) {

                    }

                });
            }
        }
    }


    /**
     * 白天夜间改变
     *
     * @param isCurDayStyle
     */
    private void changeNightAndDaySetting(boolean isCurDayStyle) {
        mSetting.setDayStyle(!isCurDayStyle);
        SysManager.saveSetting(mSetting);
        MyApplication.getApplication().setNightTheme(isCurDayStyle);
        settingChange = true;
        //mPageLoader.setPageStyle(!isCurDayStyle);
    }

    /**
     * 缩小字体
     */
    private void reduceTextSize() {
        if (mSetting.getReadWordSize() > 1) {
            mSetting.setReadWordSize(mSetting.getReadWordSize() - 1);
            SysManager.saveSetting(mSetting);
            settingChange = true;
            mPageLoader.setTextSize((int) mSetting.getReadWordSize());
        }
    }

    /**
     * 增大字体
     */
    private void increaseTextSize() {
        if (mSetting.getReadWordSize() < 41) {
            mSetting.setReadWordSize(mSetting.getReadWordSize() + 1);
            SysManager.saveSetting(mSetting);
            settingChange = true;
            mPageLoader.setTextSize((int) mSetting.getReadWordSize());
        }
    }

    /**
     * 改变阅读风格
     *
     * @param readStyle
     */
    private void changeStyle(ReadStyle readStyle) {
        settingChange = true;
        mSetting.setReadStyle(readStyle);
        SysManager.saveSetting(mSetting);
        if (!mSetting.isDayStyle()) {
            DialogCreator.createCommonDialog(mReadActivity, "提示", "是否希望切换为日间模式？",
                    false, "确定", "取消", (dialog, which) -> {
                        changeNightAndDaySetting(false);
                    }, null);
        }
        MyApplication.runOnUiThread(() -> mPageLoader.setPageStyle(true));
    }


    /**
     * 取消亮屏保持
     */
    private void unKeepScreenOn() {
        keepScreenOn(false);
    }

    /**
     * @param keepScreenOn 是否保持亮屏
     */
    public void keepScreenOn(boolean keepScreenOn) {
        if (keepScreenOn) {
            mReadActivity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            mReadActivity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    /**
     * 重置黑屏时间
     */
    private void screenOffTimerStart() {
        if (screenTimeOut <= 0) {
            keepScreenOn(true);
            return;
        }
        int screenOffTime = screenTimeOut * 1000 - ScreenHelper.getScreenOffTime(mReadActivity);
        if (screenOffTime > 0) {
            mHandler.removeCallbacks(keepScreenRunnable);
            keepScreenOn(true);
            mHandler.postDelayed(keepScreenRunnable, screenOffTime);
        } else {
            keepScreenOn(false);
        }
    }

    /**
     * 预加载下一章
     */
    private void preLoad(int position) {
        if (position + 1 < mChapters.size()) {
            Chapter chapter = mChapters.get(position + 1);
            if (StringHelper.isEmpty(chapter.getContent())) {
                mPageLoader.getChapterContent(chapter);
            }
        }
    }

    /**
     * 预加载上一章
     *
     * @param position
     */
    private void lastLoad(int position) {
        if (position > 0) {
            Chapter chapter = mChapters.get(position - 1);
            if (StringHelper.isEmpty(chapter.getContent())) {
                mPageLoader.getChapterContent(chapter);
            }
        }
    }

    /**
     * 添加本地书籍
     *
     * @param path
     */
    private void addLocalBook(String path) {
        File file = new File(path);
        if (!file.exists()) {
            return;
        }
        Book book = new Book();
        book.setName(file.getName().replace(".txt", ""));
        book.setChapterUrl(path);
        book.setType("本地书籍");
        book.setHistoryChapterId("未开始阅读");
        book.setNewestChapterTitle("未拆分章节");
        book.setAuthor("本地书籍");
        book.setSource(BookSource.local.toString());
        book.setDesc("无");
        book.setIsCloseUpdate(true);
        //判断书籍是否已经添加
        Book existsBook = mBookService.findBookByAuthorAndName(book.getName(), book.getAuthor());
        if (book.equals(existsBook)) {
            mBook = existsBook;
            return;
        }

        mBookService.addBook(book);
        mBook = book;
    }

    /**
     * 跳转到指定章节的指定页面
     *
     * @param chapterPos
     * @param pagePos
     */
    private void skipToChapterAndPage(final int chapterPos, final int pagePos) {
        isPrev = false;
        if (StringHelper.isEmpty(mChapters.get(chapterPos).getContent())) {
            if ("本地书籍".equals(mBook.getType())) {
                ToastUtils.showWarring("该章节无内容！");
                return;
            }
            mReadActivity.getPbLoading().setVisibility(View.VISIBLE);
            CommonApi.getChapterContent(mChapters.get(chapterPos).getUrl(), mReadCrawler, new ResultCallback() {
                @Override
                public void onFinish(Object o, int code) {
//                            mChapters.get(position).setContent((String) o);
                    mChapterService.saveOrUpdateChapter(mChapters.get(chapterPos), (String) o);
                    mHandler.sendMessage(mHandler.obtainMessage(2, chapterPos, pagePos));
                }

                @Override
                public void onError(Exception e) {
                    mHandler.sendMessage(mHandler.obtainMessage(2, chapterPos, pagePos));
                    mHandler.sendEmptyMessage(10);
                }
            });
        } else {
            mHandler.sendMessage(mHandler.obtainMessage(2, chapterPos, pagePos));
        }
    }

    /**
     * 自动翻页
     */
    private void autoPage() {
        mHandler.removeCallbacks(upHpbNextPage);
        mHandler.removeCallbacks(autoPageRunnable);
        if (autoPage) {
            mReadActivity.getPbNextPage().setVisibility(View.VISIBLE);
            //每页按字数计算一次时间
            nextPageTime = mPageLoader.curPageLength() * 60 * 1000 / mSetting.getAutoScrollSpeed();
            if (0 == nextPageTime) nextPageTime = 1000;
            mReadActivity.getPbNextPage().setMax(nextPageTime);
            mHandler.postDelayed(autoPageRunnable, nextPageTime);
            nextPageTime = nextPageTime - upHpbInterval * 10;
            mHandler.postDelayed(upHpbNextPage, upHpbInterval);
        } else {
            mReadActivity.getPbNextPage().setVisibility(View.INVISIBLE);
        }
    }

    /**
     * 更新自动翻页进度条
     */
    private void upHpbNextPage() {
        nextPageTime = nextPageTime - upHpbInterval;
        if (nextPageTime >= 0) {
            mReadActivity.getPbNextPage().setProgress(nextPageTime);
        }
        mHandler.postDelayed(upHpbNextPage, upHpbInterval);
    }

    /**
     * 停止自动翻页
     */
    private void autoPageStop() {
        autoPage = false;
        autoPage();
    }

    /**
     * 下一页
     */
    private void nextPage() {
        MyApplication.runOnUiThread(() -> {
            screenOffTimerStart();
            if (mPageLoader != null) {
                mPageLoader.skipToNextPage();
            }
            autoPage();
        });
    }


    /**
     * setter和getter方法
     */
    public boolean isCollected() {
        return isCollected;
    }

    public PageLoader getmPageLoader() {
        return mPageLoader;
    }

    public void setCollected(boolean collected) {
        isCollected = collected;
    }

    /**
     * ReadActivity调用
     */
    public void deleteBook() {
        mBookService.deleteBookById(mBook.getId());
    }

    /**
     * ReadActivity调用
     */
    public void onDestroy() {
        mReadActivity.unregisterReceiver(mReceiver);
        mHandler.removeCallbacks(keepScreenRunnable);
        mHandler.removeCallbacks(upHpbNextPage);
        mHandler.removeCallbacks(autoPageRunnable);
        /*mHandler.removeCallbacks(sendDownloadNotification);
        notificationUtil.cancelAll();
        MyApplication.getApplication().shutdownThreadPool();*/
        if (autoPage) {
            autoPageStop();
        }
        for (int i = 0; i < 9; i++) {
            mHandler.removeMessages(i + 1);
        }
        if (mPageLoader != null) {
            mPageLoader.closeBook();
            mPageLoader = null;
        }
    }
}

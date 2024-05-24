# Notification的创建
1.Notification的创建是从StatusBar.start() 方法开始的
2.start() 里调用 createAndAddWindows()方法
3.createAndAddWindows() 方法调用 makeStatusBarView()
4.makeStatusBarView() 方法调用 inflateStatusBarWindow()方法
5.inflateStatusBarWindow() 里创建 mNotificationShadeWindowView通知栏视图
6.inflateStatusBarWindow() 里创建 mNotificationShadeWindowViewController通知栏控制器
7.NotificationShadeWindowViewController通知栏控制器 的构造方法里通过依赖注入创建NotificationPanelViewController通知面板控制器
8.NotificationPanelViewController通知面板控制器 的构造方法里通过依赖注入创建NotificationPanelView通知面板视图
9.NotificationPanelViewController通知面板控制器 的构造方法里里调用 onFinishInflate() 方法
10.makeStatusBarView() 里绑定快捷控制视频
```
 final View container = mNotificationShadeWindowView.findViewById(R.id.qs_frame);
```
11.makeStatusBarView() 里通过FragmentHostManager给qs_frame快捷控制栏添加视图
```
FragmentHostManager fragmentHostManager = FragmentHostManager.get(container);
            ExtensionFragmentListener.attachExtensonToFragment(container, QS.TAG, R.id.qs_frame,
                    mExtensionController
                            .newExtension(QS.class)
                            .withPlugin(QS.class)
                            .withDefault(this::createDefaultQSFragment)
                            .build());
```
12.ExtensionFragmentListener.attachExtensonToFragment的实现
```
   public static <T> void attachExtensonToFragment(View view, String tag, int id,
            Extension<T> extension) {
        extension.addCallback(new ExtensionFragmentListener(view, tag, id, extension));
    }
```
13.ExtensionFragmentListener的实现
```
    private ExtensionFragmentListener(View view, String tag, int id, Extension<T> extension) {
        mTag = tag;
        mFragmentHostManager = FragmentHostManager.get(view);
        mExtension = extension;
        mId = id;
        mFragmentHostManager.getFragmentManager().beginTransaction()
                .replace(id, (Fragment) mExtension.get(), mTag)
                .commit();
        mExtension.clearItem(false);
    }
```
14.createAndAddWindows()通过mNotificationShadeWindowViewController.attach();将视图添加到WindowManager
#


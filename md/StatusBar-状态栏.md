# StatusBar的创建
1.StatusBar的启动是从 start() 方法开始的
2.start() 里调用 createAndAddWindows()方法
3.createAndAddWindows() 方法调用 makeStatusBarView()
4.makeStatusBarView() 方法调用 inflateStatusBarWindow()方法
5.inflateStatusBarWindow() 里创建 mStatusBarWindowController
6.inflateStatusBarWindow() 里创建 mPhoneStatusBarWindow
7.makeStatusBarView() 里通过FragmentHostManager给mPhoneStatusBarWindow添加视图
```
FragmentHostManager.get(mPhoneStatusBarWindow)
                .addTagListener(CollapsedStatusBarFragment.TAG, (tag, fragment) -> {
                 //省略
                }).getFragmentManager()
                .beginTransaction()
                .replace(R.id.status_bar_container, new CollapsedStatusBarFragment(),
                        CollapsedStatusBarFragment.TAG)
                .commit();
```
8.createAndAddWindows()通过mStatusBarWindowController.attach();将视图添加到WindowManager
#


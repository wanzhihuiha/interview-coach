export default defineAppConfig({
  pages: [
    'pages/home/index',
    'pages/history/index',
    'pages/mine/index',
    'pages/login/index',
    'pages/interview/config/index',
    'pages/interview/chat/index',
    'pages/interview/report/index',
    'pages/interview/growth/index',
    'pages/resume/list/index',
    'pages/position/list/index',
    'pages/position/detail/index',
  ],
  window: {
    backgroundTextStyle: 'light',
    navigationBarBackgroundColor: '#ffffff',
    navigationBarTitleText: '面试教练',
    navigationBarTextStyle: 'black',
    backgroundColor: '#f5f7fa',
  },
  tabBar: {
    color: '#909399',
    selectedColor: '#409eff',
    backgroundColor: '#ffffff',
    borderStyle: 'white',
    list: [
      {
        pagePath: 'pages/home/index',
        text: '首页',
        iconPath: 'assets/tabbar/home.svg',
        selectedIconPath: 'assets/tabbar/home-selected.svg',
      },
      {
        pagePath: 'pages/history/index',
        text: '历史',
        iconPath: 'assets/tabbar/history.svg',
        selectedIconPath: 'assets/tabbar/history-selected.svg',
      },
      {
        pagePath: 'pages/mine/index',
        text: '我的',
        iconPath: 'assets/tabbar/mine.svg',
        selectedIconPath: 'assets/tabbar/mine-selected.svg',
      },
    ],
  },
});

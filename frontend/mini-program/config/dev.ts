import type { UserConfigExport } from '@tarojs/cli';
export default {
  logger: {
    quiet: false,
    stats: true,
  },
  mini: {},
  h5: {
    devServer: {
      open: false,
      proxy: {
        '/api': {
          target: 'http://localhost:8080',
          changeOrigin: true,
        },
      },
    },
  },
} satisfies UserConfigExport<'webpack5'>;

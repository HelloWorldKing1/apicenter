/**
 * ESLint 扁平配置（2026-09-12 引入，评审 P3 #27：此前无 lint，风格一致性全靠人工）。
 * 范围：Vue3 必需规则 + JS 推荐规则；规则集刻意保守——只拦真问题，避免一次性涌入大量风格噪音。
 * 运行：`npm run lint`（随 `npm test` 不自动执行，保持测试快）。
 */
import js from '@eslint/js'
import pluginVue from 'eslint-plugin-vue'
import globals from 'globals'

export default [
  { ignores: ['dist/**', 'node_modules/**', 'src/main/resources/**'] },
  js.configs.recommended,
  ...pluginVue.configs['flat/essential'],
  {
    files: ['**/*.{js,mjs,vue}'],
    languageOptions: {
      ecmaVersion: 2023,
      sourceType: 'module',
      globals: { ...globals.browser, ...globals.node }
    },
    rules: {
      'no-unused-vars': ['warn', { args: 'none', caughtErrors: 'none', varsIgnorePattern: '^_' }],
      'no-empty': ['warn', { allowEmptyCatch: true }],
      'vue/multi-word-component-names': 'off'
    }
  },
  {
    // 参数/适配器参数编辑组件：v-model 传父级真实数组 / params 对象做**原地编辑**（见组件内注释），
    // 这是本项目的既定模式（computed 代理会丢引用），故对这两个文件关闭该规则。
    files: ['src/components/ParamTable.vue', 'src/components/AdapterParamsEditor.vue'],
    rules: {
      'vue/no-mutating-props': 'off'
    }
  }
]

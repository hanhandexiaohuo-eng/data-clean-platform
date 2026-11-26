/**
 * ECharts 按需引入。
 *
 * <p>只注册可视化分析实际用到的图表与组件，避免整包引入导致首屏体积过大。
 * 使用方统一从本模块导入 `echarts`，不要直接 `import * as echarts from 'echarts'`。
 */
import * as echarts from 'echarts/core'
import { BarChart, LineChart, PieChart, ScatterChart, HeatmapChart } from 'echarts/charts'
import {
  GridComponent,
  TooltipComponent,
  LegendComponent,
  TitleComponent,
  DataZoomComponent,
  VisualMapComponent,
  ToolboxComponent,
} from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'

echarts.use([
  BarChart,
  LineChart,
  PieChart,
  ScatterChart,
  HeatmapChart,
  GridComponent,
  TooltipComponent,
  LegendComponent,
  TitleComponent,
  DataZoomComponent,
  VisualMapComponent,
  ToolboxComponent,
  CanvasRenderer,
])

export { echarts }

/**
 * 创建一个安全可销毁的 ECharts 实例包装。
 *
 * <p>负责：重复 init 前先 dispose、窗口/容器 resize、组件卸载时释放。
 */
export function createChart(el) {
  let instance = echarts.init(el)
  let observer = null

  const resize = () => instance?.resize()
  window.addEventListener('resize', resize)
  if (typeof ResizeObserver !== 'undefined') {
    observer = new ResizeObserver(() => resize())
    observer.observe(el)
  }

  return {
    setOption(option) {
      instance?.setOption(option, true)
    },
    getInstance() {
      return instance
    },
    dispose() {
      window.removeEventListener('resize', resize)
      if (observer) {
        observer.disconnect()
        observer = null
      }
      if (instance) {
        instance.dispose()
        instance = null
      }
    },
  }
}

import { Component, type ErrorInfo, type ReactNode } from "react"
import { RotateCcw, TriangleAlert } from "lucide-react"

import { Button } from "@/components/ui/button"

/**
 * Renders a readable failure instead of a blank page.
 *
 * Data from the TV is not guaranteed to match the bundled types (an older APK, a partially
 * initialized service, an unexpected field), and an exception thrown while rendering used to leave
 * the control page completely empty with no hint about which view failed.
 */
export class ViewBoundary extends Component<{ name: string; children: ReactNode }, { error: Error | null }> {
  state: { error: Error | null } = { error: null }

  static getDerivedStateFromError(error: Error) {
    return { error }
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    // Keep the details in the console; the UI only needs enough to act on.
    console.error(`视图 ${this.props.name} 渲染失败`, error, info.componentStack)
  }

  render() {
    const { error } = this.state
    if (!error) return this.props.children
    return (
      <div className="rounded-xl border border-destructive/40 bg-destructive/10 p-5">
        <div className="flex items-center gap-2 text-sm font-medium text-destructive">
          <TriangleAlert className="size-4" />
          {this.props.name} 无法显示
        </div>
        <p className="mt-2 break-words text-sm text-destructive/90">{error.message || error.name}</p>
        <p className="mt-1 text-xs text-muted-foreground">
          这一页的数据格式与内置网页不一致，其余页面仍然可用；可先刷新，或到“日志”页查看电视端记录。
        </p>
        <Button variant="outline" size="sm" className="mt-3" onClick={() => this.setState({ error: null })}>
          <RotateCcw />重试渲染
        </Button>
      </div>
    )
  }
}

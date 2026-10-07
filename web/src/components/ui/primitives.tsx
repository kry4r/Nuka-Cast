import type { ReactNode } from "react"
import { LoaderCircle } from "lucide-react"

import { Button } from "@/components/ui/button"

/** Consistent page header: title, optional subtitle, right-aligned actions and badges. */
export function PageHeader({ title, subtitle, badges, action }: {
  title: string
  subtitle?: string
  badges?: ReactNode
  action?: ReactNode
}) {
  return (
    <header className="mb-5 flex flex-wrap items-start justify-between gap-3">
      <div className="min-w-0">
        <div className="flex flex-wrap items-center gap-2">
          <h1 className="text-xl font-semibold tracking-tight">{title}</h1>
          {badges}
        </div>
        {subtitle && <p className="mt-1 max-w-3xl text-sm leading-6 text-muted-foreground">{subtitle}</p>}
      </div>
      {action && <div className="flex flex-wrap items-center gap-2">{action}</div>}
    </header>
  )
}

export function SectionCard({ title, description, badges, action, children, className = "" }: {
  title?: string
  description?: string
  badges?: ReactNode
  action?: ReactNode
  children: ReactNode
  className?: string
}) {
  return (
    <section className={`rounded-xl border bg-card/40 ${className}`}>
      {(title || action) && (
        <div className="flex flex-wrap items-center justify-between gap-3 border-b px-4 py-3">
          <div className="min-w-0">
            <div className="flex flex-wrap items-center gap-2">
              {title && <h2 className="text-sm font-semibold">{title}</h2>}
              {badges}
            </div>
            {description && <p className="mt-1 text-xs leading-5 text-muted-foreground">{description}</p>}
          </div>
          {action && <div className="flex flex-wrap items-center gap-2">{action}</div>}
        </div>
      )}
      <div className="p-4">{children}</div>
    </section>
  )
}

export function EmptyState({ icon: Icon, title, hint, action }: {
  icon: typeof LoaderCircle
  title: string
  hint?: string
  action?: ReactNode
}) {
  return (
    <div className="flex flex-col items-center justify-center gap-2 rounded-lg border border-dashed px-4 py-8 text-center">
      <Icon className="size-7 text-muted-foreground" />
      <p className="text-sm font-medium">{title}</p>
      {hint && <p className="max-w-md text-xs leading-5 text-muted-foreground">{hint}</p>}
      {action && <div className="mt-1 flex flex-wrap items-center justify-center gap-2">{action}</div>}
    </div>
  )
}

export function Skeleton({ className = "" }: { className?: string }) {
  return <div className={`animate-pulse rounded-md bg-muted/70 ${className}`} />
}

export function CardSkeletons({ count, className = "" }: { count: number; className?: string }) {
  return (
    <>
      {Array.from({ length: count }).map((_, index) => (
        <div key={index} className={`space-y-2 ${className}`}>
          <Skeleton className="aspect-[2/3] w-full" />
          <Skeleton className="h-4 w-4/5" />
          <Skeleton className="h-3 w-2/5" />
        </div>
      ))}
    </>
  )
}

export function RowSkeletons({ count = 3 }: { count?: number }) {
  return (
    <div className="space-y-2">
      {Array.from({ length: count }).map((_, index) => (
        <Skeleton key={index} className="h-14 w-full" />
      ))}
    </div>
  )
}

/** Small status dot whose colour is always paired with text, never colour alone. */
export function StatusDot({ className = "" }: { className?: string }) {
  return <span className={`inline-block size-2 shrink-0 rounded-full ${className}`} />
}

export function Spinner({ className = "" }: { className?: string }) {
  return <LoaderCircle className={`animate-spin ${className}`} />
}

export function InlineButton({ onClick, disabled, children, title }: {
  onClick: () => void
  disabled?: boolean
  children: ReactNode
  title?: string
}) {
  return (
    <Button variant="ghost" size="sm" onClick={onClick} disabled={disabled} title={title}>
      {children}
    </Button>
  )
}

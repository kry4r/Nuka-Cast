import * as React from "react"
import { cn } from "@/lib/utils"

export const Input = React.forwardRef<HTMLInputElement, React.ComponentProps<"input">>(
  ({ className, type, ...props }, ref) => (
    <input
      type={type}
      className={cn("flex h-9 w-full rounded-md border border-input bg-transparent px-3 text-sm outline-none placeholder:text-muted-foreground focus:ring-2 focus:ring-ring disabled:opacity-50", className)}
      ref={ref}
      {...props}
    />
  ),
)
Input.displayName = "Input"

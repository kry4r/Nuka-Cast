export function createLatestRequestGate() {
  let latest = 0
  return {
    begin: () => ++latest,
    isLatest: (request: number) => request === latest,
  }
}

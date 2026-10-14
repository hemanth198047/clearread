/** Feature detection + thin wrappers for the File System Access API. */

export function supportsDirectoryPicker(): boolean {
  return typeof window !== "undefined" && typeof window.showDirectoryPicker === "function";
}

export function supportsWebkitDirectory(): boolean {
  if (typeof document === "undefined") return false;
  const input = document.createElement("input");
  return "webkitdirectory" in input;
}

export async function pickDirectory(): Promise<FileSystemDirectoryHandle> {
  const picker = typeof window !== "undefined" ? window.showDirectoryPicker : undefined;
  if (!picker) {
    throw new Error("File System Access API is not available in this browser.");
  }
  return picker({ mode: "read", id: "clearread-preferred-folder" });
}

export async function ensureReadPermission(handle: FileSystemDirectoryHandle): Promise<boolean> {
  const opts: FileSystemHandlePermissionDescriptor = { mode: "read" };
  if ((await handle.queryPermission(opts)) === "granted") return true;
  if ((await handle.requestPermission(opts)) === "granted") return true;
  return false;
}

/**
 * Resolve a nested file by relative path segments under a directory handle.
 */
export async function resolveFileHandle(
  root: FileSystemDirectoryHandle,
  relativePath: string
): Promise<FileSystemFileHandle> {
  const parts = relativePath.split("/").filter(Boolean);
  if (parts.length === 0) throw new Error("Empty path");
  let dir: FileSystemDirectoryHandle = root;
  for (let i = 0; i < parts.length - 1; i++) {
    dir = await dir.getDirectoryHandle(parts[i]!);
  }
  return dir.getFileHandle(parts[parts.length - 1]!);
}

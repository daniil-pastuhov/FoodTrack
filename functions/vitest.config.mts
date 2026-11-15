import { defineConfig } from "vitest/config";

// The tests share one Firestore and Auth emulator and reset them between tests, so files run one at a time.
// `npm test` starts the emulators through `firebase emulators:exec`.
export default defineConfig({
  test: {
    include: ["test/**/*.test.ts"],
    environment: "node",
    globals: true,
    fileParallelism: false,
    testTimeout: 30_000,
    hookTimeout: 30_000,
  },
});

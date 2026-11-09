import type { NextConfig } from "next";

/** Backend origin the /api proxy targets. Override per-env with BACKEND_URL. */
const BACKEND_URL = process.env.BACKEND_URL || "http://localhost:8080";

const nextConfig: NextConfig = {
  // Always defined, so lib/demo.ts's check is a build-time constant and a production build without
  // the flag drops the demo credentials from the bundle (an unset NEXT_PUBLIC_ var is not inlined).
  env: { NEXT_PUBLIC_DEMO_LOGINS: process.env.NEXT_PUBLIC_DEMO_LOGINS ?? "false" },
  async rewrites() {
    // Browser calls same-origin /api/... ; Next forwards to the backend, so no CORS is needed.
    // The Authorization header passes through unchanged.
    return [{ source: "/api/:path*", destination: `${BACKEND_URL}/api/:path*` }];
  },
};

export default nextConfig;

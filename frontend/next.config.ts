import type { NextConfig } from "next";

/** Backend origin the /api proxy targets. Override per-env with BACKEND_URL. */
const BACKEND_URL = process.env.BACKEND_URL || "http://localhost:8080";

const nextConfig: NextConfig = {
  async rewrites() {
    // Browser calls same-origin /api/... ; Next forwards to the backend, so no CORS is needed.
    // The Authorization header passes through unchanged.
    return [{ source: "/api/:path*", destination: `${BACKEND_URL}/api/:path*` }];
  },
};

export default nextConfig;

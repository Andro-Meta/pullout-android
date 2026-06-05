// PULLOUT nodejs-mobile entry point
process.env.API_URL            = process.env.API_URL            || "http://localhost:9000/";
process.env.API_PORT           = process.env.API_PORT           || "9000";
process.env.API_LISTEN_ADDRESS = process.env.API_LISTEN_ADDRESS || "127.0.0.1";
import("./src/cobalt.js").catch((e) => {
    console.error("[PULLOUT] cobalt boot failed:", e.message || e);
    process.exit(1);
});

(function () {
    "use strict";

    // Guard against double-install (onPageStarted + onPageFinished both inject).
    if (window.__PULLOUT_POTOKEN_INSTALLED__) {
        return;
    }
    window.__PULLOUT_POTOKEN_INSTALLED__ = true;

    var PLAYER_PATH = "/youtubei/v1/player";

    function log(msg) {
        try { console.log("[pullout-potoken] " + msg); } catch (e) {}
    }

    function deliver(body) {
        if (!body) return false;
        var json;
        try {
            json = (typeof body === "string") ? JSON.parse(body) : body;
        } catch (e) {
            return false;
        }
        try {
            var visitorData =
                json &&
                json.context &&
                json.context.client &&
                json.context.client.visitorData;
            var poToken =
                json &&
                json.serviceIntegrityDimensions &&
                json.serviceIntegrityDimensions.poToken;
            if (visitorData && poToken) {
                if (window.PoTokenBridge && window.PoTokenBridge.onToken) {
                    window.PoTokenBridge.onToken(poToken, visitorData);
                    log("delivered poToken (len=" + poToken.length + ")");
                    return true;
                }
            }
        } catch (e) {
            log("deliver error: " + e);
        }
        return false;
    }

    // ── Wrap fetch ──────────────────────────────────────────────────────────
    var originalFetch = window.fetch;
    if (originalFetch) {
        window.fetch = function (resource, options) {
            try {
                var url =
                    (typeof resource === "string")
                        ? resource
                        : (resource && resource.url) || "";
                if (url.indexOf(PLAYER_PATH) !== -1 && options && options.body) {
                    deliver(options.body);
                }
            } catch (e) {
                log("fetch wrap error: " + e);
            }
            return originalFetch.apply(this, arguments);
        };
    }

    // ── Wrap XMLHttpRequest ────────────────────────────────────────────────
    var originalOpen = XMLHttpRequest.prototype.open;
    var originalSend = XMLHttpRequest.prototype.send;

    XMLHttpRequest.prototype.open = function (method, url) {
        try {
            this.__pullout_url__ = url;
        } catch (e) {}
        return originalOpen.apply(this, arguments);
    };

    XMLHttpRequest.prototype.send = function (body) {
        try {
            var url = this.__pullout_url__ || "";
            if (url.indexOf(PLAYER_PATH) !== -1 && body) {
                deliver(body);
            }
        } catch (e) {
            log("xhr wrap error: " + e);
        }
        return originalSend.apply(this, arguments);
    };

    // ── Nudge playback so YouTube emits the /player request ────────────────
    var attempts = 0;
    var MAX_ATTEMPTS = 30; // ~15s at 500ms

    function tryPlay() {
        attempts++;
        try {
            var moviePlayer = document.querySelector("#movie_player");
            if (moviePlayer && typeof moviePlayer.click === "function") {
                moviePlayer.click();
            }
        } catch (e) {}
        try {
            var videos = document.getElementsByTagName("video");
            for (var i = 0; i < videos.length; i++) {
                var p = videos[i].play();
                if (p && typeof p.catch === "function") {
                    p.catch(function () {});
                }
            }
        } catch (e) {}
        if (attempts >= MAX_ATTEMPTS) {
            clearInterval(playTimer);
        }
    }

    var playTimer = setInterval(tryPlay, 500);

    function onReady() {
        tryPlay();
    }

    if (document.readyState === "loading") {
        document.addEventListener("DOMContentLoaded", onReady);
    } else {
        onReady();
    }

    log("installed");
})();

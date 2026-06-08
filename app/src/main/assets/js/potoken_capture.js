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

    var delivered = false;

    function send(poToken, visitorData, where) {
        if (delivered) return true;
        if (!poToken || !visitorData) return false;
        if (poToken.length < 160) {
            log("ignoring short poToken len=" + poToken.length + " (" + where + ")");
            return false;
        }
        if (window.PoTokenBridge && window.PoTokenBridge.onToken) {
            window.PoTokenBridge.onToken(poToken, visitorData);
            delivered = true;
            log("delivered poToken len=" + poToken.length + " via " + where);
            return true;
        }
        return false;
    }

    // visitor_data lives in ytcfg on modern YouTube pages.
    function getVisitorData() {
        try {
            if (window.ytcfg && typeof window.ytcfg.get === "function") {
                var v = window.ytcfg.get("VISITOR_DATA");
                if (v) return v;
            }
            if (window.ytcfg && window.ytcfg.data_ && window.ytcfg.data_.VISITOR_DATA) {
                return window.ytcfg.data_.VISITOR_DATA;
            }
            var pr = window.ytInitialPlayerResponse;
            if (pr && pr.responseContext && pr.responseContext.visitorData) {
                return pr.responseContext.visitorData;
            }
        } catch (e) {}
        return null;
    }

    // Legacy path: po_token in a /youtubei/v1/player POST body.
    function deliverFromBody(body) {
        if (!body) return false;
        var json;
        try { json = (typeof body === "string") ? JSON.parse(body) : body; }
        catch (e) { return false; }
        try {
            var visitorData = json && json.context && json.context.client &&
                json.context.client.visitorData;
            var poToken = json && json.serviceIntegrityDimensions &&
                json.serviceIntegrityDimensions.poToken;
            return send(poToken, visitorData || getVisitorData(), "player-body");
        } catch (e) { log("deliverFromBody error: " + e); return false; }
    }

    // Modern path: po_token rides the googlevideo media URL as the `pot=` param.
    function deliverFromUrl(url) {
        try {
            if (!url || url.indexOf("pot=") === -1) return false;
            var m = url.match(/[?&]pot=([^&]+)/);
            if (!m) return false;
            var poToken = decodeURIComponent(m[1]);
            return send(poToken, getVisitorData(), "gvs-url");
        } catch (e) { log("deliverFromUrl error: " + e); return false; }
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
                    deliverFromBody(options.body);
                } else if (url.indexOf("pot=") !== -1) {
                    deliverFromUrl(url);
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
            var url = String(this.__pullout_url__ || "");
            if (url.indexOf(PLAYER_PATH) !== -1 && body) {
                deliverFromBody(body);
            } else if (url.indexOf("pot=") !== -1) {
                deliverFromUrl(url);
            }
        } catch (e) {
            log("xhr wrap error: " + e);
        }
        return originalSend.apply(this, arguments);
    };

    // ── Nudge playback so YouTube emits the /player request ────────────────
    var attempts = 0;
    var MAX_ATTEMPTS = 120; // ~60s at 500ms
    var sawPlayer = false;
    var sawVideo = false;

    function tryPlay() {
        attempts++;
        try {
            var moviePlayer = document.querySelector("#movie_player");
            if (moviePlayer) {
                if (!sawPlayer) { sawPlayer = true; log("#movie_player found @attempt " + attempts); }
                // Click the big play button if present, then the player itself.
                var bigBtn = document.querySelector(".ytp-large-play-button") ||
                             document.querySelector(".ytp-play-button");
                if (bigBtn && typeof bigBtn.click === "function") bigBtn.click();
                if (typeof moviePlayer.click === "function") moviePlayer.click();
                // The embed exposes a playVideo() API on the player element.
                if (typeof moviePlayer.playVideo === "function") {
                    try { moviePlayer.playVideo(); } catch (e) {}
                }
            }
        } catch (e) {}
        try {
            var videos = document.getElementsByTagName("video");
            if (videos.length && !sawVideo) { sawVideo = true; log("video element found @attempt " + attempts); }
            for (var i = 0; i < videos.length; i++) {
                var p = videos[i].play();
                if (p && typeof p.catch === "function") {
                    p.catch(function () {});
                }
            }
        } catch (e) {}
        if (attempts === 10 || attempts === 30 || attempts === 60) {
            log("nudge attempt " + attempts + " (player=" + sawPlayer + " video=" + sawVideo + ")");
        }
        if (attempts >= MAX_ATTEMPTS) {
            clearInterval(playTimer);
            log("giving up nudge after " + attempts + " attempts");
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

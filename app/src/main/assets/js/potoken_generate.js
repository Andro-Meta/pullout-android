(function () {
    "use strict";

    if (window.__PULLOUT_BG_RUNNING__) return;
    window.__PULLOUT_BG_RUNNING__ = true;

    var REQUEST_KEY = "O43z0dpjhgX20SCx4KAo"; // well-known web request key

    function log(m) {
        try { console.log("[pullout-bg] " + m); } catch (e) {}
    }

    // visitor_data binds the po_token to this session; read it from the page.
    function getVisitorData() {
        try {
            if (window.ytcfg && typeof window.ytcfg.get === "function") {
                var v = window.ytcfg.get("VISITOR_DATA");
                if (v) return v;
            }
            if (window.ytcfg && window.ytcfg.data_ && window.ytcfg.data_.VISITOR_DATA) {
                return window.ytcfg.data_.VISITOR_DATA;
            }
        } catch (e) {}
        return null;
    }

    var delivered = false;

    async function generate() {
        if (delivered) return true;
        if (!window.BG) { log("BG bundle not present yet"); return false; }
        var visitorData = getVisitorData();
        if (!visitorData) { log("visitor_data not ready"); return false; }

        // useYouTubeAPI:true routes the BotGuard Create/GenerateIT calls through
        // www.youtube.com (same-origin on this page) instead of jnn-pa.googleapis.com,
        // avoiding the CORS block we'd hit calling Google's endpoint cross-origin.
        var bgConfig = {
            fetch: function (u, o) { return window.fetch(u, o); },
            globalObj: window,
            identifier: visitorData,
            requestKey: REQUEST_KEY,
            useYouTubeAPI: true
        };

        try {
            var challenge = await window.BG.Challenge.create(bgConfig);
            if (!challenge) { log("challenge create returned nothing"); return false; }

            var interp = challenge.interpreterJavascript &&
                challenge.interpreterJavascript.privateDoNotAccessOrElseSafeScriptWrappedValue;
            if (interp) {
                // Load the BotGuard VM into global scope.
                new Function(interp)();
            } else {
                log("no interpreter javascript in challenge");
                return false;
            }

            var result = await window.BG.PoToken.generate({
                program: challenge.program,
                globalName: challenge.globalName,
                bgConfig: bgConfig
            });

            var poToken = result && result.poToken;
            if (poToken && poToken.length >= 160) {
                if (window.PoTokenBridge && window.PoTokenBridge.onToken) {
                    window.PoTokenBridge.onToken(poToken, visitorData);
                    delivered = true;
                    log("generated poToken len=" + poToken.length);
                    return true;
                }
            } else {
                log("poToken missing/short (len=" + (poToken ? poToken.length : 0) + ")");
            }
        } catch (e) {
            log("generate error: " + (e && e.message ? e.message : e));
        }
        return false;
    }

    // Retry: visitor_data + the BG bundle may not be ready on the very first tick.
    var tries = 0;
    var timer = setInterval(function () {
        tries++;
        generate().then(function (ok) {
            if (ok || tries >= 20) {
                clearInterval(timer);
                if (!ok && tries >= 20) log("giving up after " + tries + " tries");
            }
        });
    }, 1500);

    log("generator armed");
})();

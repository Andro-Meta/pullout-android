(function () {
    "use strict";
    // YouTube enforces Trusted Types (require-trusted-types-for 'script'), which
    // blocks bgutils-js from loading the BotGuard VM via new Function(string).
    // Installing a pass-through "default" policy at document-start (before YouTube's
    // own scripts run) makes string->script sink assignments succeed again.
    try {
        if (window.trustedTypes && typeof window.trustedTypes.createPolicy === "function") {
            window.trustedTypes.createPolicy("default", {
                createHTML: function (s) { return s; },
                createScript: function (s) { return s; },
                createScriptURL: function (s) { return s; }
            });
            try { console.log("[pullout-tt] default trusted-types policy installed"); } catch (e) {}
        }
    } catch (e) {
        try { console.log("[pullout-tt] could not install policy: " + (e && e.message ? e.message : e)); } catch (e2) {}
    }
})();

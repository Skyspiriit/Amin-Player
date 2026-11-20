function createConnectionPolicy() {
    'use strict';
    var supported = [144, 240, 360, 480, 540, 720, 1080, 1440, 2160];
    function qualities(values) {
        return Array.from(new Set(values.filter(function (v) { return supported.indexOf(v) >= 0; })))
            .sort(function (a, b) { return a - b; });
    }
    function closest(values, height) {
        return values.slice().reverse().find(function (v) { return v <= height; }) || values[0];
    }
    function initialQuality(values) { return closest(qualities(values), 480); }
    function session(values) {
        var options = qualities(values);
        var cap = initialQuality(options);
        var stableSince = null;
        var cooldownUntil = 0;
        return {
            observe: function (state) {
                if (state.manual) {
                    stableSince = null;
                    cap = closest(options, state.manual);
                    return cap;
                }
                if (!state.playing || state.seeking || state.waiting) {
                    stableSince = null;
                    if (state.waiting) {
                        cap = closest(options, Math.max(options[0], (state.actual || cap) - 1));
                        cooldownUntil = state.now + 180000;
                    }
                    return cap;
                }
                if (stableSince === null) stableSince = state.now;
                if (state.now >= cooldownUntil && state.now - stableSince >= 90000 &&
                    state.buffer >= 20 && state.actual >= cap) {
                    var next = options.find(function (height) { return height > cap && height <= 1080; });
                    if (next) { cap = next; stableSince = state.now; }
                }
                return cap;
            }
        };
    }
    function bufferedAhead(video) {
        for (var index = 0; index < video.buffered.length; index++) {
            if (video.buffered.start(index) <= video.currentTime + 0.1 && video.buffered.end(index) > video.currentTime) {
                return video.buffered.end(index) - video.currentTime;
            }
        }
        return 0;
    }
    return {qualities: qualities, initialQuality: initialQuality, session: session, bufferedAhead: bufferedAhead};
}

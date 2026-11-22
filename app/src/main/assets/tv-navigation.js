function installFarsiLandTv(css, versionName, themeName, startup) {
    'use strict';

    var host = location.hostname.replace(/^www\./, '');
    if (window.top !== window || !document.body || document.readyState === 'loading' ||
        location.protocol !== 'https:' || (location.port && location.port !== '443') ||
        location.username || location.password ||
        ['farsiland.com', 'farsiland.buzz', 'farsiplex.com'].indexOf(host) < 0) return false;
    if (window.FarsiLandTv) return true;

    var playerSite = host === 'farsiland.buzz' || host === 'farsiplex.com';
    var catalogue = 'https://farsiland.com';
    var isHome = !playerSite && /^\/(?:home\/?)?$/.test(location.pathname) && !location.search;
    var isSeries = !playerSite && /^\/tvshows\/[^/]+/.test(location.pathname);
    var isEpisode = !playerSite && /^\/episodes\/[^/]+/.test(location.pathname);
    var isMovie = !playerSite && /^\/movies\/[^/]+/.test(location.pathname);
    var isFavorites = !playerSite && /^\/amin-player\/favorites\/?$/.test(location.pathname);
    var q = function (selector, root) { return (root || document).querySelector(selector); };
    var all = function (selector, root) { return Array.from((root || document).querySelectorAll(selector)); };
    var clickable = [
        'a[href]', 'button', 'input:not([type="hidden"])', 'textarea', 'select',
        '[role="button"]', '[role="tab"]', '[tabindex]:not([tabindex="-1"])',
        '.fakeplayer a', '.se-q', '#c_loginbox', 'iframe[allowfullscreen]',
        '#player iframe', '#playcontainer iframe', '.pframe iframe'
    ].join(',');
    var arrows = { ArrowLeft: 'left', ArrowRight: 'right', ArrowUp: 'up', ArrowDown: 'down' };
    var translations = {
        'Featured Titles': 'پیشنهادها', 'Recent Movies': 'فیلم‌های تازه',
        'Latest Episodes': 'قسمت‌های تازه', 'TV Series / Shows': 'سریال‌ها',
        'Seasons and episodes': 'فصل‌ها و قسمت‌ها', 'Episodes': 'قسمت‌ها',
        'TV Shows': 'سریال‌ها', 'Movies': 'فیلم‌ها', 'Home': 'خانه',
        'Info': 'معرفی', 'Cast': 'بازیگران', 'See all': 'نمایش همه',
        'PREV': 'قسمت قبلی', 'ALL': 'فهرست قسمت‌ها', 'NEXT': 'قسمت بعدی',
        'ALL SEASONS / EPISODES': 'همهٔ فصل‌ها و قسمت‌ها',
        'Login to your account': 'ورود به حساب', 'Register a new account': 'ساخت حساب',
        'Lost your password?': 'رمز عبور را فراموش کرده‌اید؟'
    };
    var current = null;
    var highlight = null;
    var activeFrame = null;
    var toolbar;
    var vpnHome;
    var searchDialog;
    var favoritesView;
    var detail;
    var detailResume = false;
    var isFavorite = false;
    var continueDialog;
    var pendingHistory;
    var qualityDialog;
    var playerLoading;
    var playerLoadingTimer;
    var seriesView;
    var closeSeriesPicker;
    var episodeMenu;
    var playUi;
    var appPort;
    var remoteTarget;
    var playback;
    var continueRow;
    var recs = null;
    var REC_INTERVAL = 12000;
    var artCache = null;
    var nextDialog;
    var nextTimer;
    var seasonLookupPending = false;
    var deferredWatch;
    var hideControlsTimer;
    var refreshTimer;
    var restored = false;
    var keyToRestore;
    var pageKey = location.pathname + location.search;

    // 1.32: three themes. Saved picks from the older fifteen themes open as the closest new one.
    var themes = [
        {id: 'cinema', name: 'سینمای طلایی', note: 'زغالی، کهربایی و طلایی', colors: ['#141114', '#2c251d', '#f2b544', '#ffd877']},
        {id: 'turquoise', name: 'شب فیروزه‌ای', note: 'سرمه‌ای و فیروزه‌ای', colors: ['#08142a', '#163055', '#2fe0d0', '#9af8ef']},
        {id: 'pomegranate', name: 'انار مخملی', note: 'شرابی، کرم و مسی', colors: ['#1b0a11', '#3d1824', '#d98b52', '#fbefdf']}
    ];
    var legacyThemes = {
        midnight: 'turquoise', saffron: 'cinema', garden: 'turquoise', galaxy: 'pomegranate', forest: 'turquoise',
        nights: 'cinema', ice: 'turquoise', dragon: 'cinema', castle: 'pomegranate', orbit: 'turquoise',
        theatre: 'pomegranate', rose: 'pomegranate', highlands: 'cinema'
    };
    var themeDialog;

    function applyTheme(id) {
        var wanted = legacyThemes[id] || id;
        var theme = themes.find(function (item) { return item.id === wanted; }) || themes[0];
        document.documentElement.setAttribute('data-fl-theme', theme.id);
        return theme.id;
    }
    var currentTheme = applyTheme(themeName);

    var style = document.createElement('style');
    style.textContent = css;
    (document.head || document.body).appendChild(style);
    document.documentElement.setAttribute('data-fl-tv', playerSite ? 'player' : 'catalogue');
    if (isHome) document.documentElement.setAttribute('data-fl-home', '');

    function make(tag, text, className) {
        var element = document.createElement(tag);
        if (text) element.textContent = text;
        if (className) element.className = className;
        return element;
    }

    // One stroke icon set (24 px grid, 2 px strokes, round joins); ".f" parts are filled.
    var ICONS = {
        home: '<path d="M3.5 11 12 4l8.5 7"/><path d="M6 9.8V20h4.5v-5.5h3V20H18V9.8"/>',
        heart: '<path class="fl-heart" d="M12 20s-7.2-4.5-9.1-8.7C1.4 8 3.3 4.6 6.7 4.6c2.2 0 3.6 1.3 5.3 3.2 1.7-1.9 3.1-3.2 5.3-3.2 3.4 0 5.3 3.4 3.8 6.7C19.2 15.5 12 20 12 20z"/>',
        search: '<circle cx="10.6" cy="10.6" r="6.3"/><path d="m15.4 15.4 5 5"/>',
        palette: '<path d="M12 3.5a8.5 8.5 0 1 0 0 17c1.3 0 1.9-.9 1.9-1.8 0-1.3-1.1-1.6-1.1-2.7 0-.9.8-1.6 1.7-1.6h2.3a3.8 3.8 0 0 0 3.8-3.8C20.6 6.6 16.8 3.5 12 3.5z"/><circle class="f" cx="7.6" cy="11.2" r="1.3"/><circle class="f" cx="10.2" cy="7.4" r="1.3"/><circle class="f" cx="14.6" cy="7.4" r="1.3"/>',
        shield: '<path d="M12 3.2 4.8 6v5.6c0 4.4 3 8 7.2 9.2 4.2-1.2 7.2-4.8 7.2-9.2V6z"/><path class="fl-shield-tick" d="m9 12.2 2.1 2.1 4-4.1"/>',
        play: '<path class="f" d="M8 5.2v13.6c0 .8.9 1.3 1.6.8l10-6.8c.6-.4.6-1.2 0-1.6l-10-6.8C8.9 3.9 8 4.4 8 5.2z"/>',
        episodes: '<rect x="3.5" y="4.5" width="17" height="15" rx="2.5"/><path d="M8 9.5h8M8 14.5h5"/>',
        previous: '<path d="M6 5.5v13"/><path class="f" d="M18.8 11.2 10.6 5.8c-.6-.4-1.4 0-1.4.8v10.8c0 .8.8 1.2 1.4.8l8.2-5.4c.6-.4.6-1.2 0-1.6z"/>',
        settings: '<circle cx="12" cy="12" r="3.1"/><path d="M12 3.2v2.6M12 18.2v2.6M3.2 12h2.6M18.2 12h2.6M5.8 5.8l1.8 1.8M16.4 16.4l1.8 1.8M5.8 18.2l1.8-1.8M16.4 7.6l1.8-1.8"/><circle cx="12" cy="12" r="6.6"/>',
        film: '<rect x="3.5" y="5" width="17" height="14" rx="2.5"/><path d="M3.5 9h17M3.5 15h17M8 5v4M16 5v4M8 15v4M16 15v4"/>'
    };

    function withIcon(element, name, label) {
        element.textContent = '';
        if (name && ICONS[name]) {
            element.insertAdjacentHTML('afterbegin', '<svg class="fl-icon" viewBox="0 0 24 24" aria-hidden="true" focusable="false">' +
                ICONS[name] + '</svg>');
        }
        element.appendChild(make('span', label, 'fl-label'));
        element.classList.add('fl-has-icon');
        return element;
    }

    function setLabel(element, text) {
        var label = q('.fl-label', element);
        if (label) label.textContent = text;
        else element.textContent = text;
    }

    function button(label, action) {
        var el = make('button', label, 'fl-button');
        el.type = 'button';
        el.addEventListener('click', action);
        return el;
    }

    function link(label, href) {
        var el = make('a', label, 'fl-button');
        el.href = href;
        return el;
    }

    function knownUrl(value) {
        try {
            var url = new URL(value, location.href);
            return url.protocol === 'https:' && !url.username && !url.password &&
                (!url.port || url.port === '443') &&
                /^(?:www\.)?(?:farsiland\.(?:com|buzz)|farsiplex\.com)$/.test(url.hostname);
        } catch (_) {
            return false;
        }
    }

    function buildOverlay(label) {
        var overlay = make('div', '', 'fl-overlay');
        overlay.dir = 'rtl';
        overlay.hidden = true;
        var box = make('section', '', 'fl-dialog');
        box.setAttribute('role', 'dialog');
        box.setAttribute('aria-modal', 'true');
        box.setAttribute('aria-label', label);
        box.appendChild(make('h2', label));
        overlay.appendChild(box);
        document.body.appendChild(overlay);
        return overlay;
    }

    function openOverlay(overlay, target, trigger) {
        overlay.hidden = false;
        overlay.flReturnFocus = trigger;
        select(target || q('button, a', overlay));
    }

    function closeOverlay(overlay) {
        overlay.hidden = true;
        select(overlay.flReturnFocus || q('.fl-button', toolbar));
    }

    function buildSearch(action) {
        searchDialog = buildOverlay('جستجو');
        var box = q('.fl-dialog', searchDialog);
        box.classList.add('fl-search-dialog');

        var heading = make('div', '', 'fl-search-heading');
        heading.appendChild(q('h2', box));
        var languages = make('div', '', 'fl-search-languages');
        languages.setAttribute('role', 'group');
        languages.setAttribute('aria-label', 'زبان صفحه‌کلید');
        heading.appendChild(languages);
        box.appendChild(heading);

        var form = make('form', '', 'fl-search-form');
        form.action = action;
        form.method = 'get';
        form.acceptCharset = 'UTF-8';
        var input = make('input', '', 'fl-search');
        input.type = 'search';
        input.name = 's';
        input.dir = 'auto';
        input.placeholder = 'نام فیلم یا سریال';
        input.setAttribute('aria-label', input.placeholder);
        input.autocomplete = 'off';
        form.appendChild(input);

        var rows = make('div', '', 'fl-search-keys');
        rows.dir = 'ltr';
        rows.setAttribute('role', 'group');
        rows.setAttribute('aria-label', 'کلیدهای جستجو');
        form.appendChild(rows);

        var layouts = {
            fa: [
                '۱ ۲ ۳ ۴ ۵ ۶ ۷ ۸ ۹ ۰',
                'ض ص ث ق ف غ ع ه خ ح ج چ',
                'ش س ی ب ل ا ت ن م ک گ',
                'ظ ط ز ر ذ د پ و ژ آ ئ ؤ ء'
            ],
            en: [
                '1 2 3 4 5 6 7 8 9 0',
                'q w e r t y u i o p',
                'a s d f g h j k l',
                'z x c v b n m'
            ]
        };
        var firstLetter;
        var persian = button('فارسی', function () { setLanguage('fa'); });
        var english = button('English', function () { setLanguage('en'); });
        english.lang = 'en';
        languages.appendChild(persian);
        languages.appendChild(english);

        function edit(value) {
            var start = input.selectionStart;
            var end = input.selectionEnd;
            if (!value && start === end) {
                var previous = Array.from(input.value.slice(0, start)).pop();
                if (previous) start -= previous.length;
            }
            input.setRangeText(value, start, end, 'end');
        }

        function setLanguage(language) {
            input.lang = language;
            rows.lang = language;
            persian.setAttribute('aria-pressed', String(language === 'fa'));
            english.setAttribute('aria-pressed', String(language === 'en'));
            rows.textContent = '';
            layouts[language].forEach(function (line) {
                var row = make('div', '', 'fl-search-key-row');
                line.split(' ').forEach(function (character) {
                    var key = button(character, function () { edit(character); });
                    if (character === (language === 'fa' ? 'ا' : 'g')) firstLetter = key;
                    row.appendChild(key);
                });
                rows.appendChild(row);
            });
        }

        var actions = make('div', '', 'fl-search-actions');
        var submit = make('button', 'جستجو', 'fl-button fl-search-submit');
        submit.type = 'submit';
        actions.appendChild(submit);
        actions.appendChild(button('فاصله', function () { edit(' '); }));
        actions.appendChild(button('حذف یک حرف', function () { edit(''); }));
        actions.appendChild(button('بستن', function () { closeOverlay(searchDialog); }));
        form.appendChild(actions);
        form.addEventListener('submit', function (event) {
            input.value = input.value.trim();
            if (!input.value) {
                event.preventDefault();
                select(firstLetter);
            }
        });
        box.appendChild(form);
        setLanguage('fa');

        var trigger = button('جستجو', function () { openOverlay(searchDialog, firstLetter, trigger); });
        withIcon(trigger, 'search', 'جستجو');
        trigger.classList.add('fl-search-trigger');
        trigger.setAttribute('aria-haspopup', 'dialog');
        return trigger;
    }

    function buildThemePicker() {
        // 1.33: one settings dialog for the theme and the startup intro.
        themeDialog = buildOverlay('تنظیمات');
        themeDialog.classList.add('fl-theme-overlay');
        var box = q('.fl-dialog', themeDialog);
        var options = make('div', '', 'fl-theme-options');
        options.setAttribute('role', 'group');
        options.setAttribute('aria-label', 'رنگ‌ها');
        var choices = themes.map(function (theme) {
            var choice = button('', function () {
                currentTheme = applyTheme(theme.id);
                choices.forEach(function (other, index) {
                    other.setAttribute('aria-pressed', String(themes[index].id === currentTheme));
                });
                postPlayback({event: 'theme', theme: currentTheme});
                closeOverlay(themeDialog);
            });
            // A small preview: the theme's own surface, a card and its focus colour.
            var preview = make('span', '', 'fl-theme-preview');
            preview.style.background = 'radial-gradient(circle at 80% 0%, ' + theme.colors[2] + '55, transparent 60%), ' + theme.colors[0];
            var sample = make('span', '', 'fl-theme-preview-card');
            sample.style.background = theme.colors[1];
            sample.style.borderColor = theme.colors[3];
            preview.appendChild(sample);
            var bar = make('span', '', 'fl-theme-preview-bar');
            bar.style.background = theme.colors[2];
            preview.appendChild(bar);
            choice.appendChild(preview);
            choice.appendChild(make('span', theme.name, 'fl-theme-name'));
            choice.appendChild(make('span', theme.note, 'fl-theme-genre'));
            choice.dataset.flTheme = theme.id;
            choice.setAttribute('aria-pressed', String(theme.id === currentTheme));
            options.appendChild(choice);
            return choice;
        });
        box.appendChild(make('h3', 'رنگ و فضای برنامه', 'fl-settings-heading'));
        box.appendChild(options);
        box.appendChild(make('h3', 'آغاز برنامه', 'fl-settings-heading'));
        var intro = {animation: !startup || startup.animation !== false, sound: !startup || startup.sound !== false};
        var switches = make('div', '', 'fl-settings-switches');
        function toggle(key, label) {
            var control = button('', function () {
                if (control.disabled) return;
                intro[key] = !intro[key];
                postPlayback({event: 'startup', animation: intro.animation, sound: intro.sound});
                show();
            });
            control.classList.add('fl-switch');
            control.appendChild(make('span', label, 'fl-label'));
            control.appendChild(make('span', '', 'fl-switch-state'));
            switches.appendChild(control);
            return control;
        }
        var animationSwitch = toggle('animation', 'انیمیشن آغاز برنامه');
        var soundSwitch = toggle('sound', 'صدای آغاز برنامه');
        function show() {
            [[animationSwitch, intro.animation], [soundSwitch, intro.sound]].forEach(function (pair) {
                pair[0].setAttribute('aria-pressed', String(pair[1]));
                q('.fl-switch-state', pair[0]).textContent = pair[1] ? 'روشن' : 'خاموش';
            });
            // The sound belongs to the animation; without it there is nothing to play.
            soundSwitch.setAttribute('aria-disabled', String(!intro.animation));
        }
        show();
        box.appendChild(switches);
        box.appendChild(make('p', 'صدا با بلندی صدای تلویزیون پخش می‌شود و وقتی صدا بسته است شنیده نمی‌شود.', 'fl-settings-note'));
        var trigger = button('تنظیمات', function () {
            openOverlay(themeDialog, q('[aria-pressed="true"]', options), trigger);
        });
        withIcon(trigger, 'settings', 'تنظیمات');
        trigger.classList.add('fl-theme-trigger');
        trigger.setAttribute('aria-haspopup', 'dialog');
        return trigger;
    }

    function addToolbar() {
        toolbar = make('nav', '', 'fl-toolbar');
        toolbar.dir = 'rtl';
        toolbar.setAttribute('aria-label', 'منوی Amin player');
        var home = withIcon(link('', catalogue + '/home'), 'home', 'خانه');
        if (isHome) home.setAttribute('aria-current', 'page');
        toolbar.appendChild(home);
        var favorites = withIcon(link('', catalogue + '/amin-player/favorites/'), 'heart', 'علاقه‌مندی');
        if (isFavorites) favorites.setAttribute('aria-current', 'page');
        toolbar.appendChild(favorites);
        var siteForm = q('#header form[role="search"]');
        var action = !playerSite && siteForm && knownUrl(siteForm.action) ? siteForm.action : catalogue + '/';
        toolbar.appendChild(buildSearch(action));
        if (isHome) toolbar.appendChild(buildThemePicker());
        if (isHome) {
            // VPN lives in the menu (1.32); its state shows as a word and a coloured dot.
            vpnHome = withIcon(button('', function () { postPlayback({event: 'vpn'}); }), 'shield', 'VPN');
            vpnHome.id = 'fl-vpn-action';
            vpnHome.classList.add('fl-vpn-button');
            vpnHome.dataset.flVpn = 'off';
            var state = make('span', 'خاموش', 'fl-vpn-state');
            state.setAttribute('aria-live', 'polite');
            vpnHome.appendChild(state);
            toolbar.appendChild(vpnHome);
        }
        if (isHome && versionName) toolbar.appendChild(make('span', 'v' + versionName, 'fl-app-version'));
        document.body.insertBefore(toolbar, document.body.firstChild);
        document.documentElement.setAttribute('data-fl-toolbar', '');
    }

    function updateVpn(state) {
        if (!vpnHome || !state || typeof state.label !== 'string' || typeof state.status !== 'string') return;
        var phase = typeof state.phase === 'string' ? state.phase : (state.stopping ? 'STOPPING' : 'OFF');
        var words = {ON: 'وصل', CONNECTING: 'در حال اتصال', STOPPING: 'در حال قطع', ERROR: 'خطا'};
        q('.fl-vpn-state', vpnHome).textContent = words[phase] || 'خاموش';
        vpnHome.dataset.flVpn = phase.toLowerCase();
        vpnHome.setAttribute('aria-label', state.label + '، ' + state.status);
        vpnHome.setAttribute('aria-disabled', state.stopping ? 'true' : 'false');
    }

    function visible(element) {
        if (!element || !element.isConnected || element.disabled || element.closest('[hidden], [inert]')) return false;
        var rect = element.getBoundingClientRect();
        if (rect.width < 3 || rect.height < 3) return false;
        var style = window.getComputedStyle(element);
        return style.visibility !== 'hidden' && style.visibility !== 'collapse' && style.display !== 'none';
    }

    function overlay() {
        if (searchDialog && !searchDialog.hidden) return searchDialog;
        var login = q('.login_box');
        if (visible(login)) return login;
        var decline = q('#up-decline');
        if (visible(decline)) return decline.parentElement.parentElement;
        var modal = all('[aria-modal="true"], dialog[open]').find(visible);
        return modal || null;
    }

    function focusTarget(element) {
        if (element.tagName !== 'A') return element;
        return element.closest('article.item, .result-item') || element;
    }

    function cardLink(element) {
        var card = focusTarget(element);
        if (card === element || !element.href) return element;
        var candidates = all('a[href]', card).filter(function (a) { return a.href === element.href && visible(a); });
        return candidates.find(function (a) { return a.closest('.poster, .thumbnail'); }) || candidates[0] || element;
    }

    function allowed(element) {
        if (!visible(element) || element.getAttribute('aria-disabled') === 'true') return false;
        if (element.closest('article.item .cloned, .owl-item.cloned')) return false;
        if (element.closest('#comments, #respond, .sbox .share, .wp-share, .fl-ignore, #slider-movies-tvshows')) return false;
        if (element.closest('iframe')) return element.tagName === 'IFRAME';
        if (episodeMenu && document.documentElement.hasAttribute('data-fl-episode-screen') &&
            element.closest('#single') && !episodeMenu.contains(element)) return false;
        if (playUi && playUi.root.contains(element) && !playUi.bar.contains(element) &&
            !(nextDialog && nextDialog.contains(element))) return false;
        if (element.tagName === 'A') {
            var href = (element.getAttribute('href') || '').trim();
            if (href && href[0] !== '#' && !knownUrl(element.href)) return false;
            if (href === '#' && element.closest('.pag_episodes') && !element.closest('.fl-quality')) return false;
            if (element.href && /\.(png|jpg|jpeg|gif|webp)(?:$|\?)/i.test(element.href)) return false;
            if (cardLink(element) !== element) return false;
        }
        return true;
    }

    function candidates() {
        var scope = overlay() || document.fullscreenElement || document.webkitFullscreenElement;
        if (!scope && playerLoading) return all(clickable, toolbar).filter(allowed);
        if (!scope && playUi && playUi.root.hasAttribute('data-fl-full')) scope = playUi.root;
        if (!scope && seriesView) return all(clickable, toolbar).concat(all(clickable, seriesView)).filter(allowed);
        if (!scope && favoritesView) return all(clickable, toolbar).concat(all(clickable, favoritesView)).filter(allowed);
        return all(clickable, scope).filter(allowed);
    }

    function typingField(element) {
        return element && (element.isContentEditable || element.tagName === 'TEXTAREA' ||
            (element.tagName === 'INPUT' && /^(text|search|password|email|url|tel|number)$/i.test(element.type)));
    }

    function focusKey(element) {
        if (!element) return '';
        if (element.id && element.tagName !== 'IFRAME') return '#' + element.id;
        var href = element.getAttribute('href');
        if (href && href !== '#') return 'link:' + href;
        if (element.classList.contains('fl-quality')) return 'quality:' + element.textContent.trim();
        if (element.matches('.fakeplayer a')) return 'watch';
        if (element.dataset.flTitleKey) return 'recent:' + element.dataset.flTitleKey;
        var toolbarButtons = toolbar ? all('.fl-button', toolbar) : [];
        if (toolbarButtons.indexOf(element) >= 0) return 'toolbar:' + toolbarButtons.indexOf(element);
        return '';
    }

    function loadFocus() {
        try { return sessionStorage.getItem('fl:focus:' + pageKey) || ''; }
        catch (_) { return ''; }
    }

    function rememberFocus(element) {
        storeFocus(pageKey, focusKey(element));
    }

    function storeFocus(targetPage, key) {
        if (!key) return;
        try {
            var index = JSON.parse(sessionStorage.getItem('fl:recent') || '[]').filter(function (x) { return x !== targetPage; });
            index.push(targetPage);
            while (index.length > 30) sessionStorage.removeItem('fl:focus:' + index.shift());
            sessionStorage.setItem('fl:recent', JSON.stringify(index));
            sessionStorage.setItem('fl:focus:' + targetPage, key);
        } catch (_) {}
    }

    function scrollTo(element) {
        var playerControls = element.closest('.fl-player-controls');
        if (playerControls && !isFullscreen()) {
            playerControls.scrollIntoView({ block: 'nearest', inline: 'nearest' });
            return;
        }
        var row = element.closest('.fl-row');
        if (row) {
            var a = element.getBoundingClientRect();
            var b = row.getBoundingClientRect();
            if (a.left < b.left + 7) row.scrollLeft += a.left - b.left - 10;
            else if (a.right > b.right - 7) row.scrollLeft += a.right - b.right + 10;
        }
        element.scrollIntoView({ block: 'nearest', inline: 'nearest' });
        if (!element.closest('.fl-toolbar, .fl-episode-menu, .fl-overlay, .login_box, .fl-player-controls, #header') && !isFullscreen()) {
            var top = element.getBoundingClientRect().top;
            var navigationBottom = (episodeMenu || toolbar).getBoundingClientRect().bottom;
            if (top < navigationBottom + 12) window.scrollBy(0, top - navigationBottom - 12);
        }
    }

    function select(element, skipScroll) {
        if (!visible(element)) return false;
        if (highlight) highlight.removeAttribute('data-fl-focus');
        current = element;
        highlight = focusTarget(element);
        highlight.setAttribute('data-fl-focus', highlight === element ? '' : 'card');
        if (!element.matches('a[href],button,input,select,textarea,[tabindex]')) element.tabIndex = -1;
        // Focus the selected DOM element; text fields enter edit mode only after OK.
        if (!typingField(element) && element.tagName !== 'IFRAME') {
            try { element.focus({ preventScroll: true }); } catch (_) { element.focus(); }
        }
        if (!skipScroll) scrollTo(highlight);
        if (!overlay()) rememberFocus(element);
        return true;
    }

    function firstChoice(elements) {
        var scope = overlay();
        if (!restored && keyToRestore && !scope) {
            var saved = elements.find(function (el) { return focusKey(el) === keyToRestore; });
            if (saved) {
                restored = true;
                return saved;
            }
        }
        if (scope) {
            return elements.find(function (el) { return el.id === 'up-decline' || el.name === 'log'; }) || elements[0];
        }
        return elements.find(function (el) {
            return el.id === 'fl-play' || el.id === 'fl-detail-play' || el.id === 'fl-episode-play' ||
                el.classList.contains('fl-favorite-card') ||
                el.classList.contains('fl-quality') || el.classList.contains('fl-resume-card') ||
                (isSeries && !!el.closest('.fl-episode-grid, #episodes .episodios')) || el.matches('.fakeplayer a');
        }) || elements.find(function (el) {
            return !el.closest('.fl-toolbar') && (!!el.closest('article.item, .result-item') || el.classList.contains('fl-rec-card'));
        }) || elements.find(function (el) {
            return isHome && /\/series-26\/$/.test(el.href);
        }) || elements[0];
    }

    function rowClips(element, bounds) {
        var row = element.closest('.fl-row');
        if (!row) return false;
        var clip = row.getBoundingClientRect();
        return Math.min(clip.right, bounds.right) - Math.max(clip.left, bounds.left) <
            Math.min(30, bounds.width / 2);
    }

    function chooseDirection(direction, elements, from) {
        var horizontal = direction === 'left' || direction === 'right';
        var sign = direction === 'left' || direction === 'up' ? -1 : 1;
        var a = focusTarget(from).getBoundingClientRect();
        var ax = (a.left + a.right) / 2;
        var ay = (a.top + a.bottom) / 2;
        var fromRow = from.closest('.fl-row, .fl-recs-row');
        var best = null;
        var bestScore = Infinity;
        var bestPoster = null;
        var bestPosterScore = Infinity;
        elements.forEach(function (target) {
            if (from === target || target.contains(from) || from.contains(target)) return;
            var b = focusTarget(target).getBoundingClientRect();
            if (rowClips(target, b) && (!horizontal || !fromRow || target.closest('.fl-row') !== fromRow)) return;
            var dx = (b.left + b.right) / 2 - ax;
            var dy = (b.top + b.bottom) / 2 - ay;
            var main = sign * (horizontal ? dx : dy);
            var cross = Math.abs(horizontal ? dy : dx);
            if (main < 4 || (horizontal && cross > (a.height + b.height) / 2)) return;
            var beam = horizontal
                ? Math.min(a.bottom, b.bottom) - Math.max(a.top, b.top)
                : Math.min(a.right, b.right) - Math.max(a.left, b.left);
            var score = main + cross * (horizontal ? 2.8 : 0.42) + (beam > 0 ? 0 : 18);
            if (fromRow && horizontal && target.closest('.fl-row, .fl-recs-row') !== fromRow) score += 300;
            if (score < bestScore) {
                best = target;
                bestScore = score;
            }
            // Up/Down prefer the nearest other row of cards (site rows and the recommendation row alike).
            var targetRow = target.closest('.fl-row, .fl-recs-row');
            if (!horizontal && fromRow && targetRow && targetRow !== fromRow && score < bestPosterScore) {
                bestPoster = target;
                bestPosterScore = score;
            }
        });
        return bestPoster || best;
    }

    function move(direction) {
        var elements = candidates();
        if (elements.indexOf(current) < 0) return select(firstChoice(elements));
        if (recs && recs.row.contains(current) && !overlay() && (direction === 'left' || direction === 'right') &&
            recs.pool.length > recs.size) {
            var recCards = all('.fl-rec-card', recs.row);
            var at = recCards.indexOf(current);
            // Right-to-left: the first card is on the right, so Left continues to the next group.
            if (direction === 'left' && at === recCards.length - 1) { renderRecs(recs.start + recs.size, 0, 'next'); return true; }
            if (direction === 'right' && at === 0) { renderRecs(recs.start - recs.size, recs.size - 1, 'prev'); return true; }
        }
        var next = chooseDirection(direction, elements, current);
        if (next) return select(next);
        return false;
    }

    function translate() {
        all('h1, h2, h3, .see-all, .pag_episodes a, .single .smenu a, .episodiotitle a, .login_box a').forEach(function (el) {
            if (el.hasAttribute('data-fl-translated') || el.childElementCount > 0) return;
            var original = el.textContent.trim();
            var episode = /^Episode\s+(\d+)$/i.exec(original);
            var fa = translations[original] || (episode && 'قسمت ' + persianNumber(episode[1]));
            if (fa) {
                el.setAttribute('data-fl-translated', '');
                el.textContent = fa;
            }
        });
        all('.fakeplayer a').forEach(function (el) { el.setAttribute('aria-label', 'پخش'); });
        var login = q('#dooplay_login_user');
        if (login) {
            var username = q('[name="log"]', login);
            var password = q('[name="pwd"]', login);
            if (username) username.placeholder = 'نام کاربری';
            if (password) password.placeholder = 'رمز عبور';
            var close = q('#c_loginbox');
            if (close) close.setAttribute('aria-label', 'بستن');
        }
    }

    function persianNumber(value) {
        return String(value).replace(/\d/g, function (digit) { return '۰۱۲۳۴۵۶۷۸۹'[Number(digit)]; });
    }

    function catalogueLink(anchor, path) {
        if (!anchor || !knownUrl(anchor.href)) return false;
        var url = new URL(anchor.href);
        return url.hostname.replace(/^www\./, '') === 'farsiland.com' && path.test(url.pathname);
    }

    function adjacentEpisode(direction) {
        return all('#single .pag_episodes a[href]').find(function (anchor) {
            var label = q('span', anchor);
            return label && label.textContent.trim() === direction &&
                catalogueLink(anchor, /^\/episodes\/[^/]+\/?$/) && new URL(anchor.href).pathname !== location.pathname;
        });
    }

    function watchTitle(title) {
        return title.trim().replace(/\s+-\s+FarsiLand$/i, '')
            .replace(/\b(?:Season\s*|SE)(\d+)\s+(?:Episode\s*|EP)(\d+)\b/gi, function (_, season, episode) {
                return '· فصل ' + persianNumber(Number(season)) + '، قسمت ' + persianNumber(Number(episode));
            }).replace(/\b(?:Episode\s*|EP)(\d+)\b/gi, function (_, episode) {
                return '· قسمت ' + persianNumber(Number(episode));
            }).replace(/^·\s*/, '');
    }

    function episodeSeries(source, base) {
        var links = all('#single .pag_episodes a[href]', source);
        for (var index = 0; index < links.length; index++) {
            var link = links[index];
            var url;
            try { url = new URL(link.getAttribute('href'), base); } catch (_) { continue; }
            if (catalogueLink(url, /^\/tvshows\/[^/]+\/?$/)) {
                return {url: url.href, title: link.getAttribute('title'), poster: pagePoster(source, base)};
            }
        }
        return null;
    }

    function seriesPageLink() {
        var series = episodeSeries(document, location.href);
        return series && new URL(series.url);
    }

    function nextSeasonKey(series) {
        return 'fl:next-season-v2:' + series.pathname.replace(/\/$/, '');
    }

    function episodeKey(page) {
        return page.pathname.replace(/\/$/, '');
    }

    function watchInfo() {
        if (!isEpisode && !isMovie) return {};
        var title = q('#single .player-title, #single .sheader .data h1');
        var poster = pagePoster(document, location.href);
        var next = isEpisode && adjacentEpisode('NEXT');
        var previous = isEpisode && adjacentEpisode('PREV');
        var series = isEpisode && episodeSeries(document, location.href);
        var following;
        if (series) {
            try {
                var boundaries = JSON.parse(sessionStorage.getItem(nextSeasonKey(new URL(series.url))) || '{}');
                following = boundaries[episodeKey(location)];
            } catch (_) {}
        }
        if (!following && next) following = { url: next.href, title: next.title };
        return {
            title: isMovie && detail ? detail.item.title : watchTitle(title ? title.textContent : document.title),
            poster: poster,
            series: series || null,
            next: following && following.url,
            nextTitle: following && watchTitle(following.title || ''),
            nextSeason: !!(following && following.nextSeason),
            previous: previous ? previous.href : null,
            previousTitle: previous ? watchTitle(previous.title || '') : null
        };
    }

    function numbersIn(text) {
        return (text.replace(/[۰-۹٠-٩]/g, function (digit) {
            var point = digit.charCodeAt(0);
            return String(point >= 0x06f0 ? point - 0x06f0 : point - 0x0660);
        }).match(/\d+/g) || []).map(Number);
    }

    function readSeasons(source, base) {
        var seasons = all('.se-c', source).map(function (section, index) {
            var marker = q('.se-q .se-t', section);
            var number = marker ? numbersIn(marker.textContent)[0] : undefined;
            var episodes = all('.episodios li', section).map(function (row, order) {
                var anchor = q('.episodiotitle a[href]', row);
                if (!anchor) return null;
                var href;
                try { href = new URL(anchor.getAttribute('href'), base); } catch (_) { return null; }
                if (!catalogueLink(href, /^\/episodes\/[^/]+\/?$/)) return null;
                var marker = q('.numerando', row);
                var numbers = numbersIn(marker ? marker.textContent : anchor.textContent);
                return { href: href.href, number: numbers[numbers.length - 1], title: anchor.textContent.trim(), order: order };
            }).filter(Boolean);
            episodes.sort(function (a, b) {
                var first = a.number === undefined ? Infinity : a.number;
                var second = b.number === undefined ? Infinity : b.number;
                return first - second || a.order - b.order;
            });
            return { number: number === undefined ? index + 1 : number, episodes: episodes, order: index };
        }).filter(function (season) { return season.episodes.length; });
        seasons.sort(function (a, b) { return a.number - b.number || a.order - b.order; });
        return seasons;
    }

    function seriesTitle(root) {
        var title = q('.sheader .data h1', root);
        var translated = q('.sheader .data h2', root);
        var persian = translated && translated.textContent.split('/').map(function (part) {
            return part.trim();
        }).find(function (part) { return /[\u0600-\u06ff]/.test(part); });
        return persian || (title && title.textContent.trim()) || '';
    }

    function pagePoster(source, base) {
        var meta = q('meta[property="og:image"]', source);
        var image = q('#single .sheader .poster img', source);
        var value = meta && meta.content || image && (image.getAttribute('data-src') || image.getAttribute('src'));
        if (!value) return null;
        try {
            var url = new URL(value, base);
            return catalogueLink(url, /^\/wp-content\/uploads\//) ? url.href : null;
        } catch (_) { return null; }
    }

    // Landscape artwork (1.32). FarsiLand names a title's wide image after its poster with "-cover";
    // only a cover that shares the page's own poster name is used, so related titles never leak in.
    function artStore() {
        if (!artCache) {
            try { artCache = JSON.parse(localStorage.getItem('fl:art') || '{}') || {}; } catch (_) { artCache = {}; }
        }
        return artCache;
    }

    function saveArt(url, value) {
        var store = artStore();
        delete store[url];
        store[url] = value || '';
        var keys = Object.keys(store);
        while (keys.length > 80) delete store[keys.shift()];
        try { localStorage.setItem('fl:art', JSON.stringify(store)); } catch (_) {}
    }

    function uploadUrl(value, base) {
        try {
            var url = new URL(value, base || location.href);
            return url.hostname.replace(/^www\./, '') === 'farsiland.com' && url.protocol === 'https:' &&
                /^\/wp-content\/uploads\//.test(url.pathname) ? url.href : null;
        } catch (_) { return null; }
    }

    function coverIn(source, base) {
        var og = q('meta[property="og:image"]', source);
        var poster = og && uploadUrl(og.content, base);
        if (!poster) return null;
        var stem = poster.replace(/(?:-\d+x\d+)?\.[a-z]+$/i, '');
        var found = null;
        // Works for a live document and for a fetched page parsed into a fragment.
        all('[src], [data-src], [data-bg], [srcset], [style]', source).some(function (element) {
            return ['src', 'data-src', 'data-bg', 'srcset', 'style'].some(function (name) {
                var value = element.getAttribute(name) || '';
                return (value.match(/https:\/\/[^"'\s)<>,]+-cover[^"'\s)<>,\/]*?\.(?:jpe?g|png|webp)/gi) || []).some(function (match) {
                    var full = uploadUrl(match.replace(/-\d+x\d+(\.[a-z]+)$/i, '$1'));
                    if (full && full.indexOf(stem + '-cover') === 0) found = full;
                    return !!found;
                });
            });
        });
        return found;
    }

    // A frame that shows wide artwork, or a composed poster (blurred fill plus the sharp poster) when only
    // a poster exists, or a quiet themed placeholder when nothing loads.
    function artwork(wide, poster, onWide, onNoWide) {
        var frame = make('span', '', 'fl-art');
        function empty() {
            frame.textContent = '';
            frame.className = 'fl-art fl-art-empty';
            frame.insertAdjacentHTML('afterbegin', '<svg class="fl-icon" viewBox="0 0 24 24" aria-hidden="true" focusable="false">' +
                ICONS.film + '</svg>');
        }
        function usePoster() {
            if (!poster) return empty();
            frame.textContent = '';
            frame.className = 'fl-art fl-art-poster';
            var fill = make('img', '', 'fl-art-fill');
            var sharp = make('img', '', 'fl-art-sharp');
            [fill, sharp].forEach(function (image) {
                image.alt = '';
                image.decoding = 'async';
                image.src = poster;
                frame.appendChild(image);
            });
            sharp.addEventListener('error', empty);
        }
        function useWide(url) {
            var image = make('img', '', 'fl-art-wide');
            image.alt = '';
            image.decoding = 'async';
            image.addEventListener('load', function () {
                if (image.naturalWidth < image.naturalHeight * 1.15) {
                    if (onNoWide && url !== poster) onNoWide();
                    usePoster();
                } else if (onWide) onWide();
            });
            image.addEventListener('error', function () {
                if (onNoWide && url !== poster) onNoWide();
                usePoster();
            });
            image.src = url;
            frame.textContent = '';
            frame.className = 'fl-art fl-art-wide-frame';
            frame.appendChild(image);
        }
        frame.flUseWide = useWide;
        if (wide) useWide(wide);
        else if (poster) useWide(poster);
        else empty();
        return frame;
    }

    // Finds landscape artwork for Continue Watching from each title's own page, a few at a time, and remembers it.
    function discoverCovers(items) {
        var queue = items.filter(function (item) {
            return !(item.url in artStore());
        }).filter(function (item) {
            try { return new URL(item.url).hostname.replace(/^www\./, '') === location.hostname.replace(/^www\./, ''); }
            catch (_) { return false; }
        });
        function next() {
            var item = queue.shift();
            if (!item || navigator.onLine === false) return;
            var abort = new AbortController();
            var timer = window.setTimeout(function () { abort.abort(); }, 4000);
            fetch(item.url, {mode: 'same-origin', signal: abort.signal}).then(function (response) {
                return response.ok ? response.text() : '';
            }).then(function (html) {
                if (!html) return;
                var cover = coverIn(readCatalogue(html), item.url);
                saveArt(item.url, cover);
                var card = continueRow && all('.fl-resume-card', continueRow).find(function (element) {
                    return element.dataset.flEpisode === item.url;
                });
                var frame = card && q('.fl-art', card);
                if (cover && frame && frame.flUseWide) frame.flUseWide(cover);
            }).catch(function () {}).finally(function () {
                window.clearTimeout(timer);
                next();
            });
        }
        next();
    }

    var genres = {
        'comedy': 'کمدی', 'drama': 'درام', 'romance': 'عاشقانه', 'action': 'اکشن', 'family': 'خانوادگی',
        'crime': 'جنایی', 'thriller': 'هیجانی', 'history': 'تاریخی', 'animation': 'انیمیشن', 'documentary': 'مستند',
        'horror': 'ترسناک', 'mystery': 'رازآلود', 'adventure': 'ماجرایی', 'music': 'موسیقی', 'war': 'جنگی',
        'fantasy': 'فانتزی', 'science fiction': 'علمی‌تخیلی', 'sci-fi & fantasy': 'علمی‌تخیلی', 'kids': 'کودک',
        'action & adventure': 'اکشن و ماجرایی', 'biography': 'زندگی‌نامه', 'reality': 'واقعی', 'talk': 'گفت‌وگو',
        'war & politics': 'جنگی و سیاسی', 'western': 'وسترن', 'sport': 'ورزشی', 'musical': 'موزیکال'
    };

    function detailMetadata(source, base) {
        var header = q('#single .sheader .data', source);
        var name = header && q('h1, h2', header) || q('#single .player-title', source);
        var subtitle = header && q(isMovie ? 'h3' : 'h2', header);
        var names = subtitle ? subtitle.textContent.split('/').map(function (part) { return part.trim(); }) : [];
        if (name) names.push(name.textContent.trim());
        names = names.filter(Boolean);
        var persian = names.find(function (part) { return /[\u0600-\u06ff]/.test(part); });
        var original = persian && names.find(function (part) { return !/[\u0600-\u06ff]/.test(part); });
        var dates = all('#single .sheader [itemprop="datePublished"], #single .sheader [itemprop="dateCreated"], ' +
            '#single .sheader .extra .date, #single .sheader .extra .year', source);
        var year = dates.map(function (date) {
            return numbersIn(date.getAttribute('datetime') || date.getAttribute('content') || date.textContent)
                .find(function (number) { return (number >= 1900 && number <= 2100) || (number >= 1250 && number <= 1500); });
        }).find(Boolean);
        var kinds = all('#single .sgeneros a', source).map(function (genre) {
            var name = genre.textContent.trim();
            return genres[name.toLowerCase()] || name;
        }).filter(function (name, index, list) { return name && list.indexOf(name) === index; }).slice(0, 3);
        var vote = q('#single .dt_rating_vgs', source);
        var rating = vote && parseFloat(vote.textContent.replace(',', '.'));
        return {url: base, title: persian || names[0], original: original, poster: pagePoster(source, base), year: year,
            cover: coverIn(source, base), genres: kinds, rating: rating > 0 && rating <= 10 ? rating : null};
    }

    function updateDetail() {
        if (!detail) return;
        setLabel(detail.favorite, isFavorite ? 'حذف از علاقه‌مندی' : 'افزودن به علاقه‌مندی');
        detail.favorite.setAttribute('aria-pressed', String(isFavorite));
        if (detail.play) setLabel(detail.play, detailResume ? 'ادامهٔ پخش' : 'پخش');
    }

    function detailHeader(item, play) {
        var header = make('section', '', 'fl-detail-hero');
        header.dir = 'rtl';
        // Large background artwork under a gradual overlay; a poster-only title shows its poster beside the text.
        var backdrop = make('div', '', 'fl-backdrop');
        backdrop.appendChild(artwork(item.cover, item.poster, function () { header.setAttribute('data-fl-wide', ''); }));
        header.appendChild(backdrop);
        if (item.cover) saveArt(item.url, item.cover);
        if (item.poster) {
            var poster = make('img', '', 'fl-detail-poster');
            poster.alt = '';
            poster.src = item.poster;
            poster.addEventListener('error', function () { poster.remove(); });
            header.appendChild(poster);
        }
        var body = make('div', '', 'fl-detail-body');
        var heading = make('h1', item.title, 'fl-detail-title');
        heading.dir = 'auto';
        body.appendChild(heading);
        var facts = [];
        if (item.year) facts.push(make('span', persianNumber(item.year), 'fl-detail-year'));
        (item.genres || []).forEach(function (genre) { facts.push(make('span', genre)); });
        if (item.rating) facts.push(make('span', '★ ' + persianNumber(item.rating.toFixed(1)).replace('.', '٫'), 'fl-detail-rating'));
        if (item.original) {
            var original = make('span', item.original, 'fl-detail-original');
            original.dir = 'auto';
            facts.push(original);
        }
        if (facts.length) {
            var meta = make('div', '', 'fl-detail-meta');
            facts.forEach(function (fact) { meta.appendChild(fact); });
            body.appendChild(meta);
        }
        var actions = make('div', '', 'fl-detail-actions');
        var primary = play && withIcon(button('', function () {
            if (detailResume) postPlayback({event:'resume-detail'});
            else play();
        }), 'play', 'پخش');
        if (primary) {
            primary.id = 'fl-detail-play';
            primary.classList.add('fl-primary');
            actions.appendChild(primary);
        }
        var favorite = withIcon(button('', function () {
            postPlayback({event:'toggle-favorite', title: {url:item.url, title:item.title, poster:item.poster}});
        }), 'heart', 'افزودن به علاقه‌مندی');
        favorite.id = 'fl-detail-favorite';
        favorite.classList.add('fl-favorite-toggle');
        actions.appendChild(favorite);
        body.appendChild(actions);
        header.appendChild(body);
        detail = {item:item, favorite:favorite, play:primary};
        updateDetail();
        return header;
    }

    function playerLink() {
        if (detail && detail.providerPlay && detail.providerPlay.isConnected) return detail.providerPlay;
        return all('.fakeplayer a').find(visible);
    }

    function checkPlayerLink(watch) {
        if (!watch) return false;
        var form = q('form[action]', watch);
        var target = form ? form.action : watch.href;
        return knownUrl(target) && /^(?:www\.)?(?:farsiland\.buzz|farsiplex\.com)$/.test(new URL(target).hostname);
    }

    function tidyDetails() {
        if (!isEpisode && !isSeries && !isMovie) return;
        all('#single .box_links').forEach(function (block) {
            if (!q('#download .links_table', block) || q('video, iframe, .fakeplayer, .pag_episodes, #seasons', block)) return;
            var tabs = all('.linktabs a', block);
            if (tabs.length && tabs.every(function (tab) { return tab.getAttribute('href') === '#download'; })) {
                block.classList.add('fl-website-only');
            } else {
                q('#download .links_table', block).classList.add('fl-website-only');
            }
        });
    }

    function mountMovie() {
        if (!isMovie || detail) return;
        var item = detailMetadata(document, location.href);
        if (!item.title) return;
        var watch = playerLink();
        var playable = checkPlayerLink(watch);
        var header = detailHeader(item, playable && function () { watch.click(); });
        header.classList.add('fl-series-hero', 'fl-movie-hero');
        var synopsis = seriesSynopsis(document);
        var body = q('.fl-detail-body', header);
        if (synopsis) body.insertBefore(make('p', synopsis, 'fl-series-synopsis'), q('.fl-detail-actions', body));
        document.body.insertBefore(header, toolbar.nextSibling);
        if (!playable) return;
        detail.providerPlay = watch;
        watch.closest('.fakeplayer').classList.add('fl-website-only');
        document.documentElement.setAttribute('data-fl-movie-detail', '');
    }

    function cacheSeasonBoundaries(seasons, series, title) {
        var boundaries = {};
        for (var index = 0; index < seasons.length - 1; index++) {
            var episodes = seasons[index].episodes;
            var last = episodes[episodes.length - 1];
            var nextSeason = seasons[index + 1];
            var first = nextSeason.episodes[0];
            var label = 'فصل ' + persianNumber(nextSeason.number) + '، ' +
                (first.number === undefined ? watchTitle(first.title) : 'قسمت ' + persianNumber(first.number));
            boundaries[episodeKey(new URL(last.href))] = {
                url: first.href, title: title ? title + ' · ' + label : label, nextSeason: true
            };
        }
        try { sessionStorage.setItem(nextSeasonKey(series), JSON.stringify(boundaries)); } catch (_) {}
        return boundaries;
    }

    function discoverNextSeason() {
        var series = seriesPageLink();
        if (!series || series.origin !== location.origin) return;
        seasonLookupPending = true;
        var abort = new AbortController();
        var timeout = window.setTimeout(function () { abort.abort(); }, 3500);
        fetch(series.href, { mode: 'same-origin', signal: abort.signal }).then(function (response) {
            return response.ok ? response.text() : '';
        }).then(function (html) {
            if (!html) return;
            var catalogue = readCatalogue(html);
            var seasonList = q('#single #seasons', catalogue);
            if (!seasonList) return;
            return cacheSeasonBoundaries(readSeasons(seasonList, series.href), series, seriesTitle(catalogue))[episodeKey(location)];
        }).catch(function () { return null; }).then(function (match) {
            window.clearTimeout(timeout);
            postPlayback({event: 'successor', url: match && match.url, title: match && match.title, nextSeason: !!match});
        });
    }

    function completeSeasonLookup() {
        if (!seasonLookupPending) return;
        seasonLookupPending = false;
        var watch = deferredWatch;
        deferredWatch = null;
        if (watch && watch.isConnected && !document.hidden && !overlay()) watch.click();
        else continuePlayback();
    }

    function waitForSeasonLookup(event) {
        if (!seasonLookupPending || !(event.target instanceof Element)) return;
        var watch = event.target.closest('.fakeplayer a');
        if (!watch) return;
        event.preventDefault();
        event.stopImmediatePropagation();
        deferredWatch = watch;
    }

    function readCatalogue(html) {
        var template = document.createElement('template');
        template.innerHTML = html;
        return template.content;
    }

    function loadSeries(currentEpisode) {
        var loading = q('#fl-series-loading');
        if (!loading) return;
        var abort = new AbortController();
        var timer = window.setTimeout(function () { abort.abort(); }, 12000);
        fetch(location.href, {mode: 'same-origin', signal: abort.signal}).then(function (response) {
            if (!response.ok) throw new Error('Series unavailable');
            return response.text().then(function (html) {
                if (!mountSeries(readCatalogue(html), response.url, currentEpisode)) throw new Error('Series unavailable');
                loading.remove();
                if (!restored && !overlay()) select(firstChoice(candidates()));
            });
        }).catch(function () {
            postPlayback({event: 'series-unavailable'});
        }).finally(function () {
            window.clearTimeout(timer);
        });
    }

    function seriesSynopsis(source) {
        var paragraphs = all('#single #info .wp-content p', source).map(function (paragraph) {
            return paragraph.textContent.replace(/\s+/g, ' ').trim();
        }).filter(Boolean);
        var persian = paragraphs.filter(function (text) { return /[\u0600-\u06ff]/.test(text); });
        return (persian.length ? persian : paragraphs).join(' ');
    }

    function mountSeries(catalogue, base, currentEpisode) {
        if (!isSeries || seriesView) return !!seriesView;
        var original = q('#single', catalogue);
        var source = original && q('#seasons', original);
        if (!original) return false;
        var seasons = source ? readSeasons(source, base) : [];
        var title = seriesTitle(original);
        var item = detailMetadata(catalogue, base);
        item.title = item.title || title;
        if (!item.title) return false;
        if (!item.year) {
            var firstAirDate = all('#info .custom_fields', original).find(function (field) {
                var label = q('.variante', field);
                return label && label.textContent.trim().toLowerCase() === 'first air date';
            });
            var date = firstAirDate && q('.valor', firstAirDate);
            item.year = date && numbersIn(date.textContent).find(function (year) { return year >= 1900 && year <= 2100; });
        }
        cacheSeasonBoundaries(seasons, location, title);
        var ordered = [];
        seasons.forEach(function (season, seasonIndex) {
            season.episodes.forEach(function (episode, episodeIndex) {
                ordered.push({item:episode, season:seasonIndex, index:episodeIndex});
            });
        });
        function findEpisode(url) {
            if (!url) return -1;
            try {
                var key = episodeKey(new URL(url, base));
                return ordered.findIndex(function (episode) { return episodeKey(new URL(episode.item.href)) === key; });
            } catch (_) { return -1; }
        }
        // Only an episode link saved by the picker or the episode's All Episodes action opens the list on return.
        var returning = keyToRestore.indexOf('link:') === 0 ? findEpisode(keyToRestore.slice(5)) : -1;
        // From the player's All Episodes button: open the list at the episode that was playing.
        var fromPlayer = /^#fl-episodes=(.+)$/.exec(location.hash);
        if (fromPlayer) {
            try { returning = findEpisode(decodeURIComponent(fromPlayer[1])); } catch (_) {}
            history.replaceState(null, '', location.pathname + location.search);
        }
        var currentIndex = returning >= 0 ? returning : findEpisode(currentEpisode);
        var previous = ordered[currentIndex - 1];

        seriesView = make('main', '', 'fl-series-view');
        seriesView.dir = 'rtl';
        var overview = detailHeader(item, ordered.length || detailResume ? function () {
            if (ordered.length) postPlayback({event:'play-series', url:ordered[0].item.href});
        } : null);
        overview.classList.add('fl-series-hero');
        var body = q('.fl-detail-body', overview);
        var synopsis = seriesSynopsis(catalogue);
        if (synopsis) body.insertBefore(make('p', synopsis, 'fl-series-synopsis'), q('.fl-detail-actions', body));
        var actionRow = q('.fl-detail-actions', body);
        var allEpisodes = withIcon(button('', function () { openPicker(currentIndex); }), 'episodes', 'همه قسمت‌ها');
        allEpisodes.id = 'fl-series-all';
        allEpisodes.disabled = !ordered.length;
        var previousEpisode = withIcon(button('', function () { if (previous) location.assign(previous.item.href); }), 'previous', 'قسمت قبلی');
        previousEpisode.id = 'fl-series-prev';
        previousEpisode.disabled = !previous;
        actionRow.insertBefore(allEpisodes, detail.favorite);
        if (previous) actionRow.insertBefore(previousEpisode, detail.favorite);
        seriesView.appendChild(overview);

        var picker = make('section', '', 'fl-series-picker');
        var pickerHeading = make('h1', item.title);
        pickerHeading.dir = 'auto';
        picker.appendChild(pickerHeading);
        seriesView.appendChild(picker);

        var seasonBar = make('div', '', 'fl-season-bar');
        seasonBar.setAttribute('role', 'group');
        seasonBar.setAttribute('aria-label', 'فصل‌ها');
        picker.appendChild(seasonBar);
        var grid = make('div', '', 'fl-episode-grid');
        grid.setAttribute('role', 'group');
        grid.setAttribute('aria-label', 'قسمت‌ها');
        picker.appendChild(grid);
        var pager = make('div', '', 'fl-episode-pager');
        var pageNumber = make('span', '', 'fl-episode-page');
        var previousPageButton = button('صفحهٔ قبلی', function () { show(activeSeason, page - 1, true); });
        var nextPageButton = button('صفحهٔ بعدی', function () { show(activeSeason, page + 1, true); });
        [previousPageButton, pageNumber, nextPageButton].forEach(function (element) { pager.appendChild(element); });
        picker.appendChild(pager);

        var pageSize = 12;
        var activeSeason = 0;
        var page = 0;
        var seasonButtons = seasons.map(function (season, index) {
            var chip = button('فصل ' + persianNumber(season.number), function () { show(index, 0, true); });
            seasonBar.appendChild(chip);
            return chip;
        });

        function show(seasonIndex, nextPage, focusFirst) {
            var season = seasons[seasonIndex];
            var pageCount = Math.ceil(season.episodes.length / pageSize);
            activeSeason = seasonIndex;
            page = Math.max(0, Math.min(nextPage, pageCount - 1));
            seasonButtons.forEach(function (chip, index) {
                chip.setAttribute('aria-pressed', String(index === activeSeason));
            });
            grid.textContent = '';
            season.episodes.slice(page * pageSize, (page + 1) * pageSize).forEach(function (episode) {
                var label = episode.number === undefined ? episode.title : 'قسمت ' + persianNumber(episode.number);
                grid.appendChild(link(label, episode.href));
            });
            pager.hidden = pageCount <= 1;
            previousPageButton.disabled = page === 0;
            nextPageButton.disabled = page === pageCount - 1;
            pageNumber.textContent = 'صفحه ' + persianNumber(page + 1) + ' از ' + persianNumber(pageCount);
            if (focusFirst) select(q('a', grid));
        }

        function showScreen(showPicker) {
            overview.hidden = showPicker;
            picker.hidden = !showPicker;
            document.documentElement.toggleAttribute('data-fl-series-overview', !showPicker);
            window.scrollTo(0, 0);
        }

        function openPicker(index) {
            if (!ordered.length) return;
            var episode = ordered[index] || ordered[0];
            showScreen(true);
            show(episode.season, Math.floor(episode.index / pageSize), false);
            restored = true;
            select(all('a', grid).find(function (link) { return link.href === episode.item.href; }) || q('a', grid));
        }

        closeSeriesPicker = function () {
            if (picker.hidden) return false;
            showScreen(false);
            select(allEpisodes, true);
            return true;
        };
        document.body.insertBefore(seriesView, toolbar.nextSibling);
        document.documentElement.setAttribute('data-fl-series-screen', '');
        document.title = item.title;
        if (returning >= 0) openPicker(returning);
        else showScreen(false);
        return true;
    }

    function mountEpisodeMenu() {
        if (!isEpisode || episodeMenu) return;
        var original = q('#single .pag_episodes');
        if (!original) return;
        var series = seriesPageLink();
        if (!series) return;

        episodeMenu = make('nav', '', 'fl-episode-menu');
        episodeMenu.dir = 'rtl';
        episodeMenu.setAttribute('aria-label', 'انتخاب قسمت');
        var watch = playerLink();
        var preview = checkPlayerLink(watch) && watch.closest('#single .content > .fakeplayer');
        if (preview) {
            // Compact screen: the site's preview stays in place (it performs the provider handoff),
            // but only these three app buttons are visible and focusable.
            var play = button('▶  پخش', function () {
                var target = playerLink();
                if (checkPlayerLink(target)) target.click();
            });
            play.id = 'fl-episode-play';
            episodeMenu.appendChild(play);
            episodeMenu.appendChild(link('همه قسمت‌ها', series.href));
            var previous = adjacentEpisode('PREV');
            if (previous) episodeMenu.appendChild(link(translations.PREV, previous.href));
            preview.parentElement.insertBefore(episodeMenu, preview.nextSibling);
            document.documentElement.setAttribute('data-fl-episode-screen', '');
        } else {
            episodeMenu.appendChild(link('همهٔ قسمت‌ها', series.href));
            ['PREV', 'NEXT'].forEach(function (direction) {
                var adjacent = adjacentEpisode(direction);
                if (adjacent) episodeMenu.appendChild(link(translations[direction], adjacent.href));
            });
            document.body.insertBefore(episodeMenu, toolbar.nextSibling);
        }
        document.documentElement.setAttribute('data-fl-episode-menu', '');
        original.classList.add('fl-episode-original');
    }

    function prepareSeriesVisit(event) {
        var anchor = event.target instanceof Element && event.target.closest('a[href]');
        if (!catalogueLink(anchor, /^\/tvshows\/[^/]+\/?$/)) return;
        var destination = new URL(anchor.href);
        if (destination.origin !== location.origin) return;
        var targetPage = destination.pathname + destination.search;
        if (isEpisode) {
            storeFocus(targetPage, 'link:' + location.origin + location.pathname);
        } else {
            try { sessionStorage.removeItem('fl:focus:' + targetPage); } catch (_) {}
        }
    }

    function markRows() {
        if (!isHome) return;
        all('.module .content .items').forEach(function (list) {
            var row = q('.owl-stage-outer, .owl-wrapper-outer', list) || list;
            all('.fl-row', list).forEach(function (previous) {
                if (previous !== row) previous.classList.remove('fl-row');
            });
            if (row !== list) list.classList.remove('fl-row');
            row.classList.add('fl-row');
        });
    }

    function qualityOf(element) {
        var quality = /^(144|240|360|480|540|720|1080|1440|2160)p?$/i.exec(element.textContent.replace(/\s/g, ''));
        return quality ? Number(quality[1]) : 0;
    }

    function markQuality() {
        if (!playerSite) return;
        all('a[href="#"], button:not(.fl-button)').forEach(function (el) {
            if (el.closest('.jwplayer')) return;
            var quality = qualityOf(el);
            if (quality && visible(el)) {
                el.classList.add('fl-quality');
                el.setAttribute('aria-label', 'کیفیت ' + persianNumber(quality));
            }
        });
    }

    function closestQuality(options, target) {
        return options.slice().reverse().find(function (option) { return option <= target; }) || options[0];
    }

    // Quality never changes by itself. Playback starts at the lowest quality the site offers, or at
    // the viewer's own pick while she stays in the player; only she changes it, from the menu.
    function startingQuality(options, requested) {
        return requested ? closestQuality(options, requested) : options[0];
    }

    function showPlayerLoading() {
        if (playerLoading || sourceVideo()) return;
        playerLoading = make('div', 'در حال آماده‌سازی پخش…', 'fl-player-loading');
        playerLoading.setAttribute('role', 'status');
        document.body.appendChild(playerLoading);
        playerLoadingTimer = window.setTimeout(function () {
            hidePlayerLoading();
            refresh();
        }, 120000);
    }

    function hidePlayerLoading() {
        if (!playerLoading) return;
        window.clearTimeout(playerLoadingTimer);
        playerLoading.remove();
        playerLoading = null;
    }

    function askForQuality(video, quality) {
        if (!playback || !video || !Number.isFinite(video.duration) || video.duration <= 0 || video.ended) return;
        postPlayback({
            event: 'change-quality', quality: quality,
            position: Math.floor(video.currentTime), duration: Math.ceil(video.duration),
            fullscreen: isFullscreen(), playing: !video.paused
        });
    }

    function openQualityPicker() {
        if (!playback || !playUi || playback.qualities.length < 2) return;
        if (qualityDialog) qualityDialog.remove();
        qualityDialog = buildOverlay('کیفیت پخش');
        qualityDialog.classList.add('fl-quality-overlay');
        var box = q('.fl-dialog', qualityDialog);
        var options = make('div', '', 'fl-quality-options');
        playback.qualities.forEach(function (resolution) {
            var option = button(persianNumber(resolution) + 'p', function () {
                closeOverlay(qualityDialog);
                if (resolution === playback.quality) return;
                playback.preferredQuality = resolution;
                askForQuality(sourceVideo(), resolution);
            });
            option.dir = 'ltr';
            option.setAttribute('aria-pressed', String(playback.quality === resolution));
            options.appendChild(option);
        });
        box.appendChild(options);
        openOverlay(qualityDialog, q('[aria-pressed="true"]', options), playUi.quality);
    }

    function postPlayback(message) {
        if (appPort) appPort.postMessage(JSON.stringify(message));
    }

    function focusedPlayerFrame() {
        if (activeFrame && !activeFrame.isConnected) activeFrame = null;
        var focused = document.activeElement;
        return activeFrame || (focused && focused.matches('iframe[data-fl-frame]') ? focused : null);
    }

    function updatePlayerRemote() {
        if (!appPort) return;
        var target = focusedPlayerFrame() ? 'frame' : 'page';
        if (target === 'page' && playUi && playUi.root.isConnected && sourceVideo() && !editing()) target = 'controls';
        if (target === remoteTarget) return;
        remoteTarget = target;
        postPlayback({event: 'remote', target: target});
    }

    function clock(seconds) {
        seconds = Math.floor(seconds);
        var hours = Math.floor(seconds / 3600);
        var minutes = Math.floor(seconds / 60);
        var text = hours ? hours + ':' + ('0' + (minutes % 60)).slice(-2) : minutes;
        return persianNumber(text + ':' + ('0' + (seconds % 60)).slice(-2));
    }

    function mountFavorites(items) {
        if (!isFavorites || favoritesView) return;
        favoritesView = make('main', '', 'fl-favorites-view');
        favoritesView.dir = 'rtl';
        favoritesView.appendChild(make('h1', 'علاقه‌مندی'));
        if (!items.length) {
            favoritesView.appendChild(make('p', 'هنوز فیلم یا سریالی به علاقه‌مندی اضافه نشده است.', 'fl-favorites-empty'));
        } else {
            var grid = make('div', '', 'fl-favorites-grid');
            items.forEach(function (item) {
                var card = link('', item.url);
                card.classList.add('fl-favorite-card');
                if (item.poster && knownUrl(item.poster)) {
                    var poster = make('img', '', 'fl-favorite-poster');
                    poster.src = item.poster;
                    poster.alt = '';
                    poster.loading = 'lazy';
                    poster.addEventListener('error', function () { poster.remove(); });
                    card.appendChild(poster);
                }
                var caption = make('span', item.title);
                caption.dir = 'auto';
                card.appendChild(caption);
                grid.appendChild(card);
            });
            favoritesView.appendChild(grid);
        }
        document.body.insertBefore(favoritesView, toolbar.nextSibling);
        if (!restored && !overlay()) {
            select(firstChoice(candidates()), true);
            if (!items.length) select(q('[aria-current="page"]', toolbar), true);
        }
    }

    function closeContinueDialog() {
        closeOverlay(continueDialog);
        if (pendingHistory) {
            var updates = pendingHistory;
            pendingHistory = null;
            mountContinueWatching(updates);
        }
    }

    function chooseContinueAction(item, card) {
        if (continueDialog) continueDialog.remove();
        continueDialog = buildOverlay(item.title);
        continueDialog.classList.add('fl-continue-overlay');
        var actions = make('div', '', 'fl-continue-actions');
        var play = button('▶ پخش', function () {
            closeContinueDialog();
            postPlayback({event:'resume', url:item.url});
        });
        var remove = button('✕ حذف از ادامه پخش', function () {
            closeContinueDialog();
            postPlayback({event:'dismiss', url:item.url});
        });
        actions.appendChild(play);
        actions.appendChild(remove);
        q('.fl-dialog', continueDialog).appendChild(actions);
        openOverlay(continueDialog, play, card);
    }

    function resolveLegacyEpisodes(episodes) {
        if (!episodes.length || navigator.onLine === false) return;
        var queue = episodes.slice();
        function next() {
            var episode = queue.shift();
            if (!episode) return Promise.resolve();
            var abort = new AbortController();
            var timer = window.setTimeout(function () { abort.abort(); }, 2500);
            return fetch(episode, {mode:'same-origin', signal:abort.signal}).then(function (response) {
                return response.ok ? response.text() : '';
            }).then(function (html) {
                var series = html && episodeSeries(readCatalogue(html), episode);
                if (series) postPlayback({event:'identify-series', url:episode, title:series});
            }).catch(function () {}).finally(function () {
                window.clearTimeout(timer);
            }).then(next);
        }
        Promise.all([next(), next()]).then(function () { postPlayback({event:'refresh-history'}); });
    }

    function mountContinueWatching(items) {
        if (continueDialog && !continueDialog.hidden) {
            pendingHistory = items;
            return;
        }
        var priorCards = continueRow ? all('.fl-resume-card', continueRow) : [];
        var oldIndex = priorCards.indexOf(current);
        var oldKey = oldIndex >= 0 ? current.dataset.flTitleKey : null;
        var oldUrl = oldIndex >= 0 ? current.dataset.flEpisode : null;
        if (continueRow) continueRow.remove();
        continueRow = null;
        if (!items.length) {
            if (oldIndex >= 0) select(q('.fl-button', toolbar), true);
            return;
        }
        continueRow = make('section', '', 'fl-continue');
        continueRow.dir = 'rtl';
        var heading = make('h2', 'ادامهٔ تماشا');
        heading.id = 'fl-continue-heading';
        continueRow.setAttribute('aria-labelledby', heading.id);
        continueRow.appendChild(heading);
        var row = make('div', '', 'fl-row');
        items.forEach(function (item) {
            var card = button('', function () { chooseContinueAction(item, card); });
            card.classList.add('fl-resume-card');
            card.dataset.flTitleKey = item.key;
            card.dataset.flEpisode = item.url;
            card.setAttribute('aria-label', item.title + (item.position > 0 ? '، ادامه از ' + clock(item.position) : ''));
            var poster = item.poster && knownUrl(item.poster) ? item.poster : null;
            var cover = artStore()[item.url] || null;
            card.appendChild(artwork(cover, poster));
            var body = make('span', '', 'fl-resume-details');
            var title = make('span', item.title, 'fl-resume-title');
            title.dir = 'auto';
            body.appendChild(title);
            if (item.episode && item.episode !== item.title) {
                var episode = make('span', item.episode, 'fl-resume-episode');
                episode.dir = 'auto';
                body.appendChild(episode);
            }
            card.appendChild(make('span', '▶ ادامهٔ پخش', 'fl-resume-cue'));
            if (item.duration > 0) {
                // A thin bar of the real saved position.
                var share = Math.max(0, Math.min(1, item.position / item.duration));
                var progress = make('span', '', 'fl-progress');
                progress.setAttribute('role', 'progressbar');
                progress.setAttribute('aria-label', 'پیشرفت تماشا');
                progress.setAttribute('aria-valuenow', String(Math.round(share * 100)));
                var fill = make('span');
                fill.style.width = Math.max(share * 100, share > 0 ? 3 : 0) + '%';
                progress.appendChild(fill);
                card.appendChild(progress);
                var time = make('span', clock(item.position) + ' / ' + clock(item.duration), 'fl-resume-time');
                time.dir = 'ltr';
                body.appendChild(time);
            }
            card.appendChild(body);
            row.appendChild(card);
        });
        continueRow.appendChild(row);
        document.body.insertBefore(continueRow, toolbar.nextSibling);
        discoverCovers(items);
        if (!overlay() && (oldIndex >= 0 || !restored)) {
            var cards = all('.fl-resume-card', row);
            var replacement = cards.find(function (card) {
                return (oldKey && oldKey === card.dataset.flTitleKey) || (oldUrl && oldUrl === card.dataset.flEpisode) ||
                    (!oldKey && focusKey(card) === keyToRestore);
            });
            select(replacement || cards[Math.min(Math.max(oldIndex, 0), cards.length - 1)], true);
        }
    }

    // Recommendations (1.32): four real titles from the home page at a time. The group rotates every 12 seconds
    // only while focus is in the top menu, pauses as soon as focus enters the row, and pages at its edges.
    function recPool() {
        var covers = {};
        all('article.homeslider').forEach(function (slide) {
            var art = q('[data-bg]', slide);
            var id = (slide.id || '').replace(/^post-/, '');
            var value = art && uploadUrl(art.getAttribute('data-bg'));
            if (id && value) covers[id] = value;
        });
        var seen = {};
        var pool = [];
        all('#featured-titles article.item, #dt_contenedor article.item.tvshows, #dt_contenedor article.item.movies').forEach(function (article) {
            if (article.closest('.cloned')) return;
            var anchor = all('a[href]', article).find(function (a) {
                return catalogueLink(a, /^\/(?:tvshows|movies|episodes)\/[^/]+/);
            });
            if (!anchor || seen[anchor.href]) return;
            var image = q('.poster img', article) || q('img', article);
            var poster = image && uploadUrl(image.getAttribute('data-src') || image.getAttribute('src'));
            var heading = q('h3', article);
            var title = (heading && heading.textContent.trim()) || (image && image.alt) || '';
            if (!title) return;
            seen[anchor.href] = true;
            var id = (article.id || '').replace(/^post-(?:featured-)?/, '');
            var known = artStore()[anchor.href];
            // The site names a title's wide image "<poster>-cover"; try it once and remember the answer.
            var guess = poster && known === undefined ? poster.replace(/(?:-\d+x\d+)?(\.[a-z]+)$/i, '-cover$1') : null;
            pool.push({url: anchor.href, title: title, poster: poster, cover: covers[id] || known || null, guess: guess,
                kind: /\/movies\//.test(anchor.pathname) ? 'فیلم' : 'سریال'});
        });
        return pool;
    }

    function recCard(item) {
        var card = make('a', '', 'fl-rec-card');
        card.href = item.url;
        card.setAttribute('aria-label', item.title + '، ' + item.kind);
        var wide = item.cover || item.guess;
        card.appendChild(artwork(wide, item.poster, function () {
            if (!item.cover && item.guess) { item.cover = item.guess; saveArt(item.url, item.guess); }
        }, function () {
            if (!item.cover && item.guess) { item.guess = null; saveArt(item.url, ''); }
        }));
        var caption = make('span', '', 'fl-rec-caption');
        var title = make('span', item.title, 'fl-rec-title');
        title.dir = 'auto';
        caption.appendChild(title);
        caption.appendChild(make('span', item.kind, 'fl-rec-kind'));
        card.appendChild(caption);
        return card;
    }

    function renderRecs(start, focusIndex, motion) {
        var count = recs.pool.length;
        recs.start = ((start % count) + count) % count;
        var cards = [];
        for (var i = 0; i < Math.min(recs.size, count); i++) cards.push(recCard(recs.pool[(recs.start + i) % count]));
        recs.row.textContent = '';
        cards.forEach(function (card) { recs.row.appendChild(card); });
        recs.row.setAttribute('data-fl-motion', motion || 'fade');
        recs.row.classList.remove('fl-recs-enter');
        void recs.row.offsetWidth;
        recs.row.classList.add('fl-recs-enter');
        var pages = Math.ceil(count / recs.size);
        recs.dots.textContent = '';
        if (pages > 1) {
            var page = Math.floor(recs.start / recs.size) % pages;
            for (var p = 0; p < pages; p++) recs.dots.appendChild(make('span', '', p === page ? 'fl-dot fl-dot-on' : 'fl-dot'));
        }
        recs.remaining = REC_INTERVAL;
        if (focusIndex !== null && focusIndex !== undefined) select(cards[Math.min(focusIndex, cards.length - 1)], true);
    }

    function recTick() {
        if (!recs || !recs.section.isConnected) return;
        if (current && recs.row.contains(current)) {
            recs.inside = true;
            return;
        }
        if (recs.inside) {
            recs.inside = false;
            recs.remaining = REC_INTERVAL;
        }
        var box = recs.row.getBoundingClientRect();
        var shown = box.height > 0 && box.top < window.innerHeight && box.bottom > 0;
        var inMenu = !!current && toolbar.contains(current);
        if (!inMenu || !shown || document.hidden || overlay() || recs.pool.length <= recs.size) return;
        recs.remaining -= 1000;
        if (recs.remaining <= 0) renderRecs(recs.start + recs.size, null, 'fade');
    }

    function mountRecommendations() {
        if (!isHome || recs || !toolbar) return;
        var pool = recPool();
        if (!pool.length) return;
        var section = make('section', '', 'fl-recs');
        section.dir = 'rtl';
        var head = make('div', '', 'fl-recs-head');
        var heading = make('h2', 'پیشنهادها');
        heading.id = 'fl-recs-heading';
        section.setAttribute('aria-labelledby', heading.id);
        head.appendChild(heading);
        var dots = make('span', '', 'fl-dots');
        dots.setAttribute('aria-hidden', 'true');
        head.appendChild(dots);
        section.appendChild(head);
        var row = make('div', '', 'fl-recs-row');
        section.appendChild(row);
        // Our row replaces the site's featured carousel; the other site rows stay below.
        all('#featured-titles, #featload').forEach(function (element) { element.classList.add('fl-replaced'); });
        all('#dt_contenedor header').forEach(function (siteHeader) {
            var title = q('h2', siteHeader);
            if (title && /^(?:Featured Titles|پیشنهادها)$/.test(title.textContent.trim())) siteHeader.classList.add('fl-replaced');
        });
        document.body.insertBefore(section, continueRow ? continueRow.nextSibling : toolbar.nextSibling);
        recs = {section: section, row: row, dots: dots, pool: pool, size: 4, start: 0, remaining: REC_INTERVAL, inside: false};
        // Returning from a title shows its group again; otherwise each visit starts at the next group.
        var back = keyToRestore.indexOf('link:') === 0 ? pool.findIndex(function (item) {
            return 'link:' + item.url === keyToRestore;
        }) : -1;
        var start = back;
        if (back < 0) {
            var visit = 0;
            try { visit = Number(localStorage.getItem('fl:recs-visit')) || 0; } catch (_) {}
            try { localStorage.setItem('fl:recs-visit', String(visit + 1)); } catch (_) {}
            start = pool.length > recs.size ? (visit * recs.size) % pool.length : 0;
        }
        renderRecs(start, null, 'fade');
        window.setInterval(recTick, 1000);
    }

    function connectPlayback(event) {
        if (typeof event.data !== 'string' || !event.ports || event.ports.length !== 1) return;
        var config;
        try { config = JSON.parse(event.data); } catch (_) { return; }
        if (!config || config.app !== 'AminPlayer' || (event.source && event.source !== window)) return;
        if (appPort) appPort.close();
        appPort = event.ports[0];
        appPort.onmessage = function (message) {
            // Wait until Android has saved the result before leaving the catalogue page.
            if (message.data === 'season-lookup-complete') completeSeasonLookup();
            if (message.data === 'quality-options-ready' && playback) {
                playback.qualityReady = true;
                continuePlayback();
            }
            if (message.data[0] === '{') {
                var update;
                try { update = JSON.parse(message.data); } catch (_) { return; }
                if (isHome && Array.isArray(update.history)) mountContinueWatching(update.history);
                if (isHome && update.vpn) updateVpn(update.vpn);
                if (typeof update.favorite === 'boolean') {
                    isFavorite = update.favorite;
                    updateDetail();
                }
            }
        };
        remoteTarget = null;
        isFavorite = config.favorite === true;
        detailResume = config.detailResume === true;
        updateDetail();
        if (isHome && Array.isArray(config.history)) mountContinueWatching(config.history);
        if (isHome && config.vpn) updateVpn(config.vpn);
        if (isHome && Array.isArray(config.legacyEpisodes)) resolveLegacyEpisodes(config.legacyEpisodes);
        if (isFavorites && Array.isArray(config.favorites)) mountFavorites(config.favorites);
        if (isSeries && config.series) loadSeries(config.seriesEpisode);
        if (isEpisode && config.findNextSeason) discoverNextSeason();
        if (config.player || config.openPlayer) {
            playback = {
                openPlayer: config.openPlayer === true,
                next: config.next,
                previous: config.previous,
                allEpisodes: config.allEpisodes === true,
                nextSeason: config.nextSeason === true,
                autoPlay: config.startVideo === true,
                playing: !config.resume || config.resume.playing !== false,
                resumeAt: config.resume ? config.resume.position : 0,
                requestedQuality: config.resume && config.resume.requestedQuality,
                preferredQuality: config.preferredQuality,
                qualities: config.qualities || [],
                quality: config.quality,
                fullscreen: !config.resume || config.resume.fullscreen !== false,
                started: false,
                finished: false,
                lastReport: 0
            };
            if (playback.autoPlay) showPlayerLoading();
            refresh();
        }
        updatePlayerRemote();
    }

    function continuePlayback() {
        if (!playback || document.hidden || overlay()) return;
        if (playback.openPlayer) {
            if (seasonLookupPending) return;
            var watch = playerLink();
            if (!checkPlayerLink(watch)) return;
            playback.openPlayer = false;
            watch.click();
            return;
        }
        if (!playback.autoPlay) return;
        var qualities = all('.fl-quality').filter(visible);
        if (qualities.length && !playback.qualityChosen) {
            if (playback.pendingQuality) {
                if (playback.qualityReady && playback.pendingQuality.isConnected) {
                    playback.pendingQuality.click();
                    playback.pendingQuality = null;
                }
                return;
            }
            var options = Array.from(new Set(qualities.map(qualityOf))).sort(function (a, b) { return a - b; });
            var selected = startingQuality(options, playback.requestedQuality || playback.preferredQuality);
            playback.qualities = options;
            playback.quality = selected;
            playback.pendingQuality = qualities.find(function (element) { return qualityOf(element) === selected; });
            postPlayback({event: 'quality-options', options: options, selected: selected});
            return;
        }
        if (!sourceVideo()) return;
        if (playback.resumeAt && !Number.isFinite(sourceVideo().duration)) return;
        playback.autoPlay = false;
        hidePlayerLoading();
        if (playback.fullscreen) enterInPageFullscreen();
        applyResumePosition();
        if (playback.playing) media('play');
        else sourceVideo().pause();
    }

    function applyResumePosition() {
        var video = sourceVideo();
        if (!playback || !playback.resumeAt || !video || !Number.isFinite(video.duration) || video.duration <= 0) return;
        var position = Math.min(playback.resumeAt, Math.max(0, video.duration - 1));
        if (!video.seeking && Math.abs(video.currentTime - position) < 1) {
            playback.resumeAt = 0;
            return;
        }
        // FarsiPlex may initialize/reload its player after metadata arrives. Keep the
        // resume request until the media confirms it, and use the player's seek API.
        if (playback.resumeSeekAt && Date.now() - playback.resumeSeekAt < 1500) return;
        playback.resumeSeekAt = Date.now();
        try {
            seekTo(position);
        } catch (_) { /* The next media event can retry once the player is ready. */ }
    }

    function reportProgress(force) {
        var video = sourceVideo();
        if (!playback || !playback.started || playback.finished || playback.resumeAt || !video ||
            !Number.isFinite(video.duration) || video.duration <= 0 || !Number.isFinite(video.currentTime)) return;
        if (!force && Date.now() - playback.lastReport < 3000) return;
        playback.lastReport = Date.now();
        postPlayback({ event: 'progress', position: Math.floor(video.currentTime), duration: Math.ceil(video.duration) });
    }

    function dismissNextEpisode() {
        window.clearInterval(nextTimer);
        if (!nextDialog || nextDialog.hidden) return false;
        closeOverlay(nextDialog);
        return true;
    }

    function playNextEpisode() {
        if (!dismissNextEpisode() || document.hidden) return;
        postPlayback({event: 'next', fullscreen: isFullscreen()});
    }

    function promptNextEpisode() {
        if (!playback.next || document.hidden) return;
        dismissNextEpisode();
        if (nextDialog) nextDialog.remove();
        nextDialog = buildOverlay(playback.nextSeason ? 'فصل بعدی را پخش کنیم؟' : 'قسمت بعدی را پخش کنیم؟');
        nextDialog.classList.add('fl-next-overlay');
        var fullscreenElement = document.fullscreenElement || document.webkitFullscreenElement;
        if (fullscreenElement) fullscreenElement.appendChild(nextDialog);
        var box = q('.fl-dialog', nextDialog);
        var title = make('p', playback.next);
        title.dir = 'auto';
        box.appendChild(title);
        var timer = make('p', '', 'fl-next-countdown');
        box.appendChild(timer);
        var actions = make('div', '', 'fl-next-actions');
        var next = button(playback.nextSeason ? 'پخش فصل بعدی' : 'پخش قسمت بعدی', playNextEpisode);
        actions.appendChild(next);
        actions.appendChild(button('بعداً', dismissNextEpisode));
        box.appendChild(actions);
        nextDialog.hidden = false;
        nextDialog.flReturnFocus = playUi.play;
        select(next, true);

        var deadline = performance.now() + 5000;
        function tick() {
            if (document.hidden || !nextDialog.isConnected || !playback.finished) {
                dismissNextEpisode();
                return;
            }
            var seconds = Math.ceil((deadline - performance.now()) / 1000);
            if (seconds <= 0) {
                playNextEpisode();
                return;
            }
            var label = 'پخش خودکار تا ' + persianNumber(seconds) + ' ثانیهٔ دیگر';
            if (timer.textContent !== label) timer.textContent = label;
        }
        nextTimer = window.setInterval(tick, 100);
        tick();
    }

    function getPlayerApi() {
        try {
            if (!playUi || typeof window.jwplayer !== 'function' || !playUi.root.id) return null;
            var api = window.jwplayer(playUi.root.id);
            return typeof api.getState === 'function' ? api : null;
        } catch (_) { return null; }
    }

    function sourceVideo() { return playUi && q('video', playUi.root); }

    function seekTo(position) {
        var video = sourceVideo();
        if (!video || !Number.isFinite(video.duration) || video.duration <= 0) return false;
        var target = Math.max(0, Math.min(video.duration, position));
        var api = getPlayerApi();
        if (api) api.seek(target);
        else video.currentTime = target;
        return true;
    }

    function commitTimeline() {
        if (!playUi || playUi.preview === null) return;
        var position = playUi.preview;
        window.clearTimeout(playUi.seekTimer);
        playUi.preview = null;
        if (playback) playback.resumeAt = 0;
        try { seekTo(position); } catch (_) { playError(); }
        updatePlayUi();
    }

    function moveTimeline(direction, repeat) {
        var video = sourceVideo();
        if (!video || playUi.timeline.disabled) return;
        var position = playUi.preview === null ? video.currentTime : playUi.preview;
        var step = repeat ? Math.max(15, Math.round(video.duration / 100)) : 5;
        var delta = direction === 'right' ? step : -step;
        playUi.preview = Math.max(0, Math.min(video.duration, position + delta));
        window.clearTimeout(playUi.seekTimer);
        playUi.seekTimer = window.setTimeout(commitTimeline, 220);
        updatePlayUi();
    }

    function media(command) {
        if (command === 'pause') {
            dismissNextEpisode();
            deferredWatch = null;
            if (playback) {
                playback.autoPlay = false;
                playback.openPlayer = false;
                reportProgress(true);
            }
        }
        var video = sourceVideo();
        var api = getPlayerApi();
        if (!api && !video) return false;
        var playing = video ? !video.paused && !video.ended : api.getState() === 'playing';
        try {
            if (command === 'toggle' || command === 'play' || command === 'pause') {
                var play = command === 'play' || (command === 'toggle' && !playing);
                var result = api ? (play ? api.play(true) : api.pause(true)) : (play ? video.play() : video.pause());
                if (result && typeof result.catch === 'function') result.catch(playError);
            } else {
                var position = api ? api.getPosition() : video.currentTime;
                if (!Number.isFinite(position) || !seekTo(position + (command === 'forward' ? 10 : -10))) return false;
            }
        } catch (_) {
            if (command !== 'pause') playError();
            return false;
        }
        revealControls();
        updatePlayUi();
        return true;
    }

    function playError() {
        if (!playUi) return;
        playUi.status.textContent = 'پخش ممکن نشد. دوباره امتحان کنید یا بازگشت را بزنید.';
        revealControls();
    }

    function isFullscreen() {
        return !!(document.fullscreenElement || document.webkitFullscreenElement ||
            (playUi && playUi.root.hasAttribute('data-fl-full')));
    }

    function exitFullscreen() {
        // Respect an explicit exit even if the first playing event is still pending.
        if (playback) playback.fullscreen = false;
        if (document.fullscreenElement && document.exitFullscreen) document.exitFullscreen();
        else if (document.webkitFullscreenElement && document.webkitExitFullscreen) document.webkitExitFullscreen();
        else if (playUi) {
            playUi.root.removeAttribute('data-fl-full');
            document.documentElement.removeAttribute('data-fl-lock');
            if (playUi.fullscreenPlaceholder) {
                playUi.fullscreenPlaceholder.replaceWith(playUi.root);
                playUi.fullscreenPlaceholder = null;
            }
            updatePlayUi();
            scrollTo(playUi.bar);
        }
        return true;
    }

    function enterInPageFullscreen() {
        if (!playUi || !playUi.root.isConnected) return false;
        if (isFullscreen()) return true;
        var placeholder = document.createComment('Amin player fullscreen');
        playUi.root.before(placeholder);
        playUi.fullscreenPlaceholder = placeholder;
        document.body.appendChild(playUi.root);
        playUi.root.setAttribute('data-fl-full', '');
        document.documentElement.setAttribute('data-fl-lock', '');
        updatePlayUi();
        return true;
    }

    function toggleFullscreen() {
        if (isFullscreen()) return exitFullscreen();
        // Keep the same focused WebView for the timeline and next-episode prompt.
        enterInPageFullscreen();
        revealControls();
    }

    function revealControls() {
        if (!playUi) return;
        playUi.bar.classList.remove('fl-controls-hidden');
        window.clearTimeout(hideControlsTimer);
        var video = sourceVideo();
        if (video && !video.paused && !video.ended && !playUi.status.textContent) {
            hideControlsTimer = window.setTimeout(function () {
                if (playUi) playUi.bar.classList.add('fl-controls-hidden');
            }, 6500);
        }
    }

    function updatePlayUi() {
        if (!playUi) return;
        var video = sourceVideo();
        var playing = video && !video.paused && !video.ended;
        var label = playing ? '❚❚ مکث' : '▶ پخش';
        if (playUi.play.textContent !== label) playUi.play.textContent = label;
        playUi.next.hidden = !(playback && playback.next);
        playUi.previous.hidden = !(playback && playback.previous);
        if (playUi.episodes) playUi.episodes.hidden = !(playback && playback.allEpisodes);
        var resolution = playback && playback.quality;
        if (playUi.quality) {
            playUi.quality.hidden = !resolution || playback.qualities.length < 2;
            var qualityLabel = resolution ? 'کیفیت ' + persianNumber(resolution) + 'p' : 'کیفیت';
            if (playUi.quality.textContent !== qualityLabel) playUi.quality.textContent = qualityLabel;
        }
        var full = isFullscreen() ? 'خروج از تمام‌صفحه' : 'تمام‌صفحه';
        if (playUi.full.textContent !== full) playUi.full.textContent = full;
        var seekable = video && Number.isFinite(video.duration) && video.duration > 0;
        playUi.timeline.disabled = !seekable;
        if (seekable) {
            var seconds = playUi.preview === null ? video.currentTime : playUi.preview;
            playUi.timeline.max = video.duration;
            playUi.timeline.value = seconds;
            var time = clock(seconds) + ' / ' + clock(video.duration);
            if (playUi.time.textContent !== time) {
                playUi.time.textContent = time;
                playUi.timeline.setAttribute('aria-valuetext', time);
            }
            playUi.timeline.style.setProperty('--fl-progress', 100 * seconds / video.duration + '%');
        }
    }

    function mountPlayer() {
        var jw = all('.jwplayer').find(function (root) { return visible(root) && q('video', root); });
        var video = jw ? q('video', jw) : all('video').find(visible);
        if (!video) return;
        var root = jw || video.parentElement;
        if (playUi && playUi.root === root && playUi.bar.isConnected) return;
        if (playUi) {
            window.clearTimeout(playUi.seekTimer);
            playUi.bar.remove();
        }
        root.classList.add('fl-player-root');
        markPlaybackPath(root);
        var bar = make('div', '', 'fl-player-controls');
        bar.dir = 'rtl';
        var status = make('div', '', 'fl-player-status');
        status.setAttribute('role', 'status');
        bar.appendChild(status);
        var controls = make('div', '', 'fl-controls');
        var play = button('پخش', function () { media('toggle'); });
        play.id = 'fl-play';
        var full = button('تمام‌صفحه', toggleFullscreen);
        var quality = button('کیفیت', openQualityPicker);
        quality.classList.add('fl-quality-control');
        quality.setAttribute('aria-haspopup', 'dialog');
        var time = make('span', '', 'fl-player-time');
        time.dir = 'ltr';
        // Series only: skip to the previous or next episode (Android knows both URLs).
        var nextEpisode = button('قسمت بعدی ▶', function () { skipEpisode('next'); });
        var previousEpisode = button('◀ قسمت قبلی', function () { skipEpisode('previous'); });
        // 1.34: leave the player for this series' episode list, opened at the episode playing now.
        var episodeList = button('☰ همه قسمت‌ها', function () {
            if (!playback || !playback.allEpisodes) return;
            dismissNextEpisode();
            reportProgress(true);
            postPlayback({event: 'all-episodes'});
        });
        nextEpisode.id = 'fl-skip-next';
        previousEpisode.id = 'fl-skip-previous';
        episodeList.id = 'fl-all-episodes';
        [nextEpisode, previousEpisode, episodeList].forEach(function (skip) {
            skip.classList.add('fl-skip');
            skip.hidden = true;
        });
        [nextEpisode, play, episodeList, previousEpisode, full, quality].forEach(function (element) {
            controls.appendChild(element);
        });
        var scrubRow = make('div', '', 'fl-scrub');
        scrubRow.dir = 'ltr';
        var timeline = make('input', '', 'fl-timeline');
        timeline.type = 'range';
        timeline.min = 0;
        timeline.step = 'any';
        timeline.disabled = true;
        timeline.setAttribute('aria-label', 'نوار پخش. با چپ و راست زمان را تغییر دهید.');
        timeline.addEventListener('input', function () {
            playUi.preview = Number(timeline.value);
            revealControls();
            updatePlayUi();
        });
        timeline.addEventListener('change', commitTimeline);
        scrubRow.appendChild(timeline);
        scrubRow.appendChild(time);
        bar.appendChild(scrubRow);
        bar.appendChild(controls);
        root.appendChild(bar);
        playUi = { root: root, bar: bar, status: status, play: play, full: full, quality: quality, time: time, timeline: timeline, preview: null,
            next: nextEpisode, previous: previousEpisode, episodes: episodeList };
        hidePlayerLoading();
        if (!overlay() && (!current || current.closest('.fl-toolbar') || !visible(current))) select(play);
        updatePlayUi();
    }

    // Playback screen: keep the player and the elements leading to it; every other site section
    // stays in the DOM but is hidden (CSS), so it cannot take remote focus or make the page scroll.
    function markPlaybackPath(root) {
        if (!playerSite) return;
        all('.fl-playback-path, .fl-playback-keep, .fl-playback-frame').forEach(function (element) {
            element.classList.remove('fl-playback-path', 'fl-playback-keep', 'fl-playback-frame');
        });
        // The site sizes the player with percentage padding, which follows the width of the
        // player's grandparent; narrowing that box keeps the 16:9 player inside the screen.
        var frame = root.parentElement && root.parentElement.parentElement;
        if (frame && frame !== document.body) frame.classList.add('fl-playback-frame');
        [root].concat(all('.fl-quality')).forEach(function (element) {
            if (element !== root) element.classList.add('fl-playback-keep');
            for (var node = element.parentElement; node && node !== document.body; node = node.parentElement) {
                node.classList.add('fl-playback-path');
            }
        });
        document.documentElement.setAttribute('data-fl-playback', '');
    }

    function skipEpisode(direction) {
        if (!playback || !(direction === 'next' ? playback.next : playback.previous)) return;
        dismissNextEpisode();
        reportProgress(true);
        postPlayback({event: 'skip', direction: direction, fullscreen: isFullscreen()});
    }

    function frameHelp(frame) {
        if (frame.hasAttribute('data-fl-frame')) return;
        frame.setAttribute('data-fl-frame', '');
        frame.title = frame.title || 'پخش‌کنندهٔ ویدیو';
        var hint = make('p', 'برای ورود به پخش‌کننده OK را بزنید. با بالا، پایین یا بازگشت به منوی برنامه برگردید.', 'fl-frame-hint');
        hint.dir = 'rtl';
        frame.parentElement.insertBefore(hint, frame);
    }

    function refresh(refocus) {
        markRows();
        mountRecommendations();
        markQuality();
        mountEpisodeMenu();
        mountMovie();
        tidyDetails();
        translate();
        mountPlayer();
        continuePlayback();
        all('iframe[allowfullscreen], #player iframe, #playcontainer iframe, .pframe iframe').filter(visible).forEach(frameHelp);
        updatePlayerRemote();
        if (focusedPlayerFrame()) return;
        var elements = candidates();
        var preferred = firstChoice(elements);
        if (elements.indexOf(current) < 0) select(preferred);
        else if (refocus === true && !editing()) select(current);
    }

    function scheduleRefresh() {
        if (refreshTimer) return;
        refreshTimer = window.setTimeout(function () {
            refreshTimer = 0;
            refresh();
        }, 100);
    }

    function activate() {
        if (!current || !visible(current)) refresh();
        if (!current || !visible(current)) return;
        if (typingField(current)) {
            current.focus();
            return;
        }
        if (current.tagName === 'IFRAME') {
            activeFrame = current;
            current.focus();
            updatePlayerRemote();
            return;
        }
        if (current.tagName === 'SELECT') {
            if (current.showPicker) {
                try { current.showPicker(); } catch (_) {}
            }
            return;
        }
        current.click();
        scheduleRefresh();
    }

    function editing() { return !!typingField(document.activeElement); }

    function leavePlayerFrame(frame) {
        activeFrame = null;
        frame.blur();
        (q('.fl-button', toolbar) || document.body).focus({ preventScroll: true });
        updatePlayerRemote();
    }

    function back() {
        if (dismissNextEpisode()) return true;
        if (continueDialog && !continueDialog.hidden) {
            closeContinueDialog();
            return true;
        }
        if (qualityDialog && !qualityDialog.hidden) {
            closeOverlay(qualityDialog);
            return true;
        }
        if (themeDialog && !themeDialog.hidden) {
            closeOverlay(themeDialog);
            return true;
        }
        if (editing()) {
            document.activeElement.blur();
            return true;
        }
        if (isFullscreen()) return exitFullscreen();
        var frame = focusedPlayerFrame();
        if (frame) {
            leavePlayerFrame(frame);
            select(frame);
            return true;
        }
        var modal = overlay();
        if (modal === searchDialog) {
            closeOverlay(modal);
            return true;
        }
        if (modal) {
            var dismiss = q('#c_loginbox, #up-decline, [data-dismiss], [aria-label="Close"]', modal);
            if (dismiss) {
                dismiss.click();
                scheduleRefresh();
                return true;
            }
        }
        if (closeSeriesPicker && closeSeriesPicker()) return true;
        return false;
    }

    function playerDirection(direction, repeat) {
        restored = true;
        var frame = focusedPlayerFrame();
        if (frame) {
            if (direction !== 'up' && direction !== 'down') return;
            leavePlayerFrame(frame);
            var target = direction === 'up' ? q('.fl-button', toolbar) : chooseDirection('down', candidates(), frame);
            select(target || frame);
            return;
        }
        if (!playUi || !playUi.root.isConnected || overlay()) return move(direction);
        if (editing()) return;
        if (playUi.bar.contains(current)) {
            revealControls();
            if (current === playUi.timeline) {
                if (direction === 'left' || direction === 'right') return moveTimeline(direction, repeat);
                commitTimeline();
                if (direction === 'down') return select(playUi.returnButton) || select(playUi.play);
                if (!isFullscreen()) return select(q('.fl-button', toolbar));
                return;
            }
            if (direction === 'up' && !playUi.timeline.disabled) {
                playUi.returnButton = current;
                return select(playUi.timeline);
            }
        } else if (direction === 'down' && current && toolbar.contains(current)) {
            revealControls();
            return select(playUi.timeline.disabled ? playUi.play : playUi.timeline);
        }
        return move(direction);
    }

    function onKey(event) {
        var direction = arrows[event.key];
        var ok = event.key === 'Enter' || event.key === 'Select' || event.keyCode === 23;
        if ((!ok && !direction) || event.isComposing || focusedPlayerFrame()) return;
        if (editing()) {
            if (direction !== 'up' && direction !== 'down') return;
            document.activeElement.blur();
        }
        event.preventDefault();
        event.stopImmediatePropagation();
        restored = true;
        if (direction) return playerDirection(direction, event.repeat);
        if (event.repeat) return;
        var onPlayer = playUi && playUi.bar.contains(current);
        var wasHidden = onPlayer && playUi.bar.classList.contains('fl-controls-hidden');
        if (onPlayer) revealControls();
        if (onPlayer && current === playUi.timeline) return commitTimeline();
        if (!wasHidden || current === playUi.play) activate();
    }

    addToolbar();
    keyToRestore = loadFocus();
    window.FarsiLandTv = {
        back: back, editing: editing, media: media, refresh: refresh, watchInfo: watchInfo, playerDirection: playerDirection
    };
    document.addEventListener('focusin', updatePlayerRemote);
    document.addEventListener('focusout', function () { window.setTimeout(updatePlayerRemote, 0); });
    document.addEventListener('click', waitForSeasonLookup, true);
    document.addEventListener('click', prepareSeriesVisit, true);
    document.addEventListener('click', function (event) {
        var option = event.target instanceof Element && event.target.closest('.fl-quality');
        if (!option || !playback) return;
        playback.qualityChosen = true;
        postPlayback({event: 'quality', quality: qualityOf(option)});
    }, true);
    window.addEventListener('message', connectPlayback);
    window.addEventListener('pagehide', function () {
        dismissNextEpisode();
        deferredWatch = null;
        reportProgress(true);
    });
    window.addEventListener('keydown', onKey, true);
    window.addEventListener('pageshow', scheduleRefresh);
    window.addEventListener('resize', scheduleRefresh);
    document.addEventListener('fullscreenchange', function () { updatePlayUi(); revealControls(); });
    document.addEventListener('webkitfullscreenchange', function () { updatePlayUi(); revealControls(); });
    ['play', 'playing', 'pause', 'ended', 'timeupdate', 'loadedmetadata', 'durationchange', 'seeked', 'error', 'waiting'].forEach(function (type) {
        document.addEventListener(type, function (event) {
            if (event.target !== sourceVideo()) return;
            if (type === 'error') playError();
            if (type === 'play') playUi.status.textContent = '';
            if (playback) {
                if (type === 'loadedmetadata' || type === 'playing' || type === 'seeked' || type === 'timeupdate') applyResumePosition();
                if (type === 'loadedmetadata' || type === 'durationchange') continuePlayback();
                if (type === 'playing') {
                    if (!playback.started && playback.fullscreen) enterInPageFullscreen();
                    if (playback.finished) {
                        dismissNextEpisode();
                        postPlayback({event: 'replay'});
                    }
                    playback.started = true;
                    playback.finished = false;
                }
                if (type === 'ended' && playback.started && !playback.finished && event.target.ended) {
                    playback.finished = true;
                    if (qualityDialog && !qualityDialog.hidden) closeOverlay(qualityDialog);
                    postPlayback({event: 'ended'});
                    promptNextEpisode();
                }
                reportProgress(type === 'playing' || type === 'pause' || type === 'seeked');
            }
            if (type === 'play' || type === 'pause' || type === 'ended') revealControls();
            updatePlayUi();
        }, true);
    });
    new MutationObserver(function (records) {
        if (records.some(function (record) {
            return !playUi || !playUi.bar.contains(record.target);
        })) scheduleRefresh();
    }).observe(document.body, {
        childList: true, subtree: true, attributes: true,
        attributeFilter: ['style', 'hidden', 'aria-hidden', 'aria-modal', 'open', 'src']
    });
    refresh();
    return true;
}

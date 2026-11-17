function installFarsiLandTv(css, versionName) {
    'use strict';

    var host = location.hostname.replace(/^www\./, '');
    if (window.top !== window || !document.body || document.readyState === 'loading' ||
        location.protocol !== 'https:' || (location.port && location.port !== '443') ||
        ['farsiland.com', 'farsiland.buzz'].indexOf(host) < 0) return false;
    if (window.FarsiLandTv) return true;

    var playerSite = host === 'farsiland.buzz';
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
    var nextDialog;
    var nextTimer;
    var seasonLookupPending = false;
    var deferredWatch;
    var hideControlsTimer;
    var refreshTimer;
    var restored = false;
    var keyToRestore;
    var pageKey = location.pathname + location.search;

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
                /^(?:www\.)?farsiland\.(com|buzz)$/.test(url.hostname);
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
        trigger.classList.add('fl-search-trigger');
        trigger.setAttribute('aria-haspopup', 'dialog');
        return trigger;
    }

    function addToolbar() {
        toolbar = make('nav', '', 'fl-toolbar');
        toolbar.dir = 'rtl';
        toolbar.setAttribute('aria-label', 'منوی Amin player');
        if (isHome && versionName) toolbar.appendChild(make('span', 'v' + versionName, 'fl-app-version'));
        toolbar.appendChild(link('خانه', catalogue + '/home'));
        var favorites = link('علاقه‌مندی', catalogue + '/amin-player/favorites/');
        if (isFavorites) favorites.setAttribute('aria-current', 'page');
        toolbar.appendChild(favorites);
        var siteForm = q('#header form[role="search"]');
        var action = !playerSite && siteForm && knownUrl(siteForm.action) ? siteForm.action : catalogue + '/';
        toolbar.appendChild(buildSearch(action));
        document.body.insertBefore(toolbar, document.body.firstChild);
        document.documentElement.setAttribute('data-fl-toolbar', '');
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
            return el.id === 'fl-play' || el.id === 'fl-detail-play' || el.classList.contains('fl-favorite-card') ||
                el.classList.contains('fl-quality') || el.classList.contains('fl-resume-card') ||
                (isSeries && !!el.closest('.fl-episode-grid, #episodes .episodios')) || el.matches('.fakeplayer a');
        }) || elements.find(function (el) {
            return !el.closest('.fl-toolbar') && !!el.closest('article.item, .result-item');
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
        var fromRow = from.closest('.fl-row');
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
            if (fromRow && horizontal && target.closest('.fl-row') !== fromRow) score += 300;
            if (score < bestScore) {
                best = target;
                bestScore = score;
            }
            if (!horizontal && fromRow && target.closest('.fl-row') &&
                target.closest('.fl-row') !== fromRow && score < bestPosterScore) {
                bestPoster = target;
                bestPosterScore = score;
            }
        });
        return bestPoster || best;
    }

    function move(direction) {
        var elements = candidates();
        if (elements.indexOf(current) < 0) return select(firstChoice(elements));
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
            nextSeason: !!(following && following.nextSeason)
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
        return {url: base, title: persian || names[0], original: original, poster: pagePoster(source, base), year: year};
    }

    function updateDetail() {
        if (!detail) return;
        detail.favorite.textContent = isFavorite ? '♥ حذف از علاقه‌مندی' : '♡ افزودن به علاقه‌مندی';
        detail.favorite.setAttribute('aria-pressed', String(isFavorite));
        if (detail.play) detail.play.textContent = detailResume ? 'ادامهٔ پخش' : 'پخش';
    }

    function detailHeader(item, play) {
        var header = make('section', '', 'fl-detail-hero');
        header.dir = 'rtl';
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
        if (item.original || item.year) {
            var meta = make('div', '', 'fl-detail-meta');
            if (item.original) {
                var original = make('span', item.original);
                original.dir = 'auto';
                meta.appendChild(original);
            }
            if (item.year) meta.appendChild(make('span', persianNumber(item.year), 'fl-detail-year'));
            body.appendChild(meta);
        }
        var actions = make('div', '', 'fl-detail-actions');
        var primary = play && button('پخش', function () {
            if (detailResume) postPlayback({event:'resume-detail'});
            else play();
        });
        if (primary) {
            primary.id = 'fl-detail-play';
            actions.appendChild(primary);
        }
        var favorite = button('', function () {
            postPlayback({event:'toggle-favorite', title: {url:item.url, title:item.title, poster:item.poster}});
        });
        favorite.id = 'fl-detail-favorite';
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
        return knownUrl(target) && /^(?:www\.)?farsiland\.buzz$/.test(new URL(target).hostname);
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
        var shortcuts = make('div', '', 'fl-series-shortcuts');
        var allEpisodes = button('همه قسمت‌ها', function () { openPicker(currentIndex); });
        allEpisodes.id = 'fl-series-all';
        allEpisodes.disabled = !ordered.length;
        var previousEpisode = button('قسمت قبلی', function () { if (previous) location.assign(previous.item.href); });
        previousEpisode.id = 'fl-series-prev';
        previousEpisode.disabled = !previous;
        shortcuts.appendChild(allEpisodes);
        shortcuts.appendChild(previousEpisode);
        body.appendChild(shortcuts);
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
        episodeMenu.appendChild(link('همهٔ قسمت‌ها', series.href));
        ['PREV', 'NEXT'].forEach(function (direction) {
            var adjacent = adjacentEpisode(direction);
            if (adjacent) episodeMenu.appendChild(link(translations[direction], adjacent.href));
        });
        document.body.insertBefore(episodeMenu, toolbar.nextSibling);
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
        var quality = /^(480|720|1080|1440|2160)p?$/i.exec(element.textContent.replace(/\s/g, ''));
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

    function automaticQuality(options) {
        var connection = navigator.connection;
        var rate = connection && Number(connection.downlink);
        var ceiling = 720;
        if (Number.isFinite(rate) && rate > 0) {
            ceiling = rate >= 20 ? 2160 : rate >= 12 ? 1440 : rate >= 8 ? 1080 : rate >= 3 ? 720 : 480;
        }
        return closestQuality(options, ceiling);
    }

    function showPlayerLoading() {
        if (playerLoading || sourceVideo()) return;
        playerLoading = make('div', 'در حال آماده‌سازی پخش…', 'fl-player-loading');
        playerLoading.setAttribute('role', 'status');
        document.body.appendChild(playerLoading);
        playerLoadingTimer = window.setTimeout(function () {
            hidePlayerLoading();
            refresh();
        }, 12000);
    }

    function hidePlayerLoading() {
        if (!playerLoading) return;
        window.clearTimeout(playerLoadingTimer);
        playerLoading.remove();
        playerLoading = null;
    }

    function askForQuality(video, quality, automatic, buffering) {
        if (!playback || !video || !Number.isFinite(video.duration) || video.duration <= 0 || video.ended) return;
        postPlayback({
            event: 'change-quality', quality: quality, automatic: automatic, buffering: buffering,
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
        var auto = button('خودکار', function () {
            closeOverlay(qualityDialog);
            var target = playback.preferredQuality ? automaticQuality(playback.qualities) : playback.quality;
            playback.preferredQuality = null;
            askForQuality(sourceVideo(), target, true, false);
        });
        auto.setAttribute('aria-pressed', String(!playback.preferredQuality));
        options.appendChild(auto);
        playback.qualities.forEach(function (resolution) {
            var option = button(persianNumber(resolution) + 'p', function () {
                closeOverlay(qualityDialog);
                playback.preferredQuality = resolution;
                askForQuality(sourceVideo(), resolution, false, false);
            });
            option.dir = 'ltr';
            option.setAttribute('aria-pressed', String(playback.preferredQuality === resolution));
            options.appendChild(option);
        });
        box.appendChild(options);
        openOverlay(qualityDialog, q('[aria-pressed="true"]', options), playUi.quality);
    }

    function watchBuffering(video) {
        if (!playback || !playback.started || playback.preferredQuality || playback.bufferTimer ||
            playback.downgradeRequested || video.paused || video.seeking || document.hidden ||
            video.currentTime < 5 || video.duration - video.currentTime < 10) return;
        var lower = closestQuality(playback.qualities, playback.quality - 1);
        if (!lower || lower >= playback.quality) return;
        playback.bufferTimer = window.setTimeout(function () {
            playback.bufferTimer = 0;
            if (document.hidden || video.paused || video.seeking || video.ended || video.readyState >= 3 ||
                playback.preferredQuality) return;
            playback.downgradeRequested = true;
            askForQuality(video, lower, true, true);
        }, 5500);
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
            if (item.poster && knownUrl(item.poster)) {
                var image = make('img', '', 'fl-resume-poster');
                image.src = item.poster;
                image.alt = '';
                image.addEventListener('error', function () { image.remove(); });
                card.appendChild(image);
            }
            var body = make('span', '', 'fl-resume-details');
            var title = make('span', item.title, 'fl-resume-title');
            title.dir = 'auto';
            body.appendChild(title);
            if (item.episode && item.episode !== item.title) {
                var episode = make('span', item.episode, 'fl-resume-episode');
                episode.dir = 'auto';
                body.appendChild(episode);
            }
            if (item.duration > 0) {
                var progress = make('progress');
                progress.max = item.duration;
                progress.value = item.position;
                progress.setAttribute('aria-label', 'پیشرفت تماشا');
                body.appendChild(progress);
                var time = make('span', clock(item.position) + ' / ' + clock(item.duration), 'fl-resume-time');
                time.dir = 'ltr';
                body.appendChild(time);
            }
            card.appendChild(body);
            row.appendChild(card);
        });
        continueRow.appendChild(row);
        document.body.insertBefore(continueRow, toolbar.nextSibling);
        if (!overlay() && (oldIndex >= 0 || !restored)) {
            var cards = all('.fl-resume-card', row);
            var replacement = cards.find(function (card) {
                return (oldKey && oldKey === card.dataset.flTitleKey) || (oldUrl && oldUrl === card.dataset.flEpisode) ||
                    (!oldKey && focusKey(card) === keyToRestore);
            });
            select(replacement || cards[Math.min(Math.max(oldIndex, 0), cards.length - 1)], true);
        }
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
        if (isHome && Array.isArray(config.legacyEpisodes)) resolveLegacyEpisodes(config.legacyEpisodes);
        if (isFavorites && Array.isArray(config.favorites)) mountFavorites(config.favorites);
        if (isSeries && config.series) loadSeries(config.seriesEpisode);
        if (isEpisode && config.findNextSeason) discoverNextSeason();
        if (config.player || config.openPlayer) {
            playback = {
                openPlayer: config.openPlayer === true,
                next: config.next,
                nextSeason: config.nextSeason === true,
                autoPlay: config.startVideo === true,
                playing: !config.resume || config.resume.playing !== false,
                resumeAt: config.resume ? config.resume.position : 0,
                requestedQuality: config.resume && config.resume.requestedQuality,
                preferredQuality: config.preferredQuality,
                qualities: config.qualities || [],
                quality: config.quality,
                fullscreen: config.resume && config.resume.fullscreen,
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
            var requested = playback.requestedQuality || playback.preferredQuality;
            var selected = requested ? closestQuality(options, requested) : automaticQuality(options);
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
        try {
            video.currentTime = position;
            playback.resumeAt = 0;
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
        var placeholder = document.createComment('Amin player fullscreen');
        playUi.root.before(placeholder);
        playUi.fullscreenPlaceholder = placeholder;
        document.body.appendChild(playUi.root);
        playUi.root.setAttribute('data-fl-full', '');
        document.documentElement.setAttribute('data-fl-lock', '');
        updatePlayUi();
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
        var label = playing ? 'مکث' : 'پخش';
        if (playUi.play.textContent !== label) playUi.play.textContent = label;
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
        [play, button('۱۰ ثانیه عقب', function () { media('rewind'); }),
            button('۱۰ ثانیه جلو', function () { media('forward'); }), full, quality].forEach(function (element) {
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
        playUi = { root: root, bar: bar, status: status, play: play, full: full, quality: quality, time: time, timeline: timeline, preview: null };
        hidePlayerLoading();
        if (!overlay() && (!current || current.closest('.fl-toolbar') || !visible(current))) select(play);
        updatePlayUi();
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
                if (type === 'waiting') watchBuffering(event.target);
                if (type === 'playing' || type === 'pause' || type === 'seeked') {
                    window.clearTimeout(playback.bufferTimer);
                    playback.bufferTimer = 0;
                }
                if (type === 'loadedmetadata' || type === 'playing') applyResumePosition();
                if (type === 'loadedmetadata' || type === 'durationchange') continuePlayback();
                if (type === 'playing') {
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

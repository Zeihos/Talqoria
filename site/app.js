(() => {
  const validPages = new Set(['home', 'world', 'guide', 'market', 'community', 'cabinet', 'staff']);
  const pages = document.querySelectorAll('[data-page]');
  const links = document.querySelectorAll('[data-nav]');
  const nav = document.getElementById('nav-links');
  const toggle = document.querySelector('.menu-toggle');
  const dialog = document.getElementById('join-dialog');
  function navigate() {
    const page = location.hash.slice(1) || 'home';
    const active = validPages.has(page) ? page : 'home';
    for (const el of pages) el.hidden = el.dataset.page !== active;
    for (const link of links) {
      const isActive = link.getAttribute('href') === `#${active}`;
      link.classList.toggle('active', isActive);
      if (isActive) link.setAttribute('aria-current', 'page');
      else link.removeAttribute('aria-current');
    }
    document.title = `${active === 'home' ? 'TALQORIA' : ({world:'Мир', guide:'Как играть', market:'Рынок', community:'Сообщество', cabinet:'Кабинет', staff:'Панель команды'})[active] + ' — TALQORIA'} — создаём свой мир`;
    nav?.classList.remove('open');
    toggle?.setAttribute('aria-expanded', 'false');
    window.scrollTo({ top: 0, behavior: 'auto' });
  }
  toggle?.addEventListener('click', () => {
    const opened = nav.classList.toggle('open');
    toggle.setAttribute('aria-expanded', String(opened));
  });
  for (const btn of document.querySelectorAll('[data-open-join]')) btn.addEventListener('click', () => dialog?.showModal());
  for (const btn of document.querySelectorAll('[data-close-dialog]')) btn.addEventListener('click', () => dialog?.close());
  dialog?.addEventListener('click', e => { if (e.target === dialog) dialog.close(); });
  addEventListener('hashchange', navigate);
  document.getElementById('year').textContent = new Date().getFullYear();
  navigate();

  function put(selector, value) {
    for (const el of document.querySelectorAll(selector)) el.textContent = value;
  }
  async function updateStatus() {
    try {
      const result = await fetch('/api/status', { cache: 'no-store', signal: AbortSignal.timeout(5000) });
      if (!result.ok) throw new Error('API unavailable');
      const data = await result.json();
      if (!data.connected) {
        put('[data-live-status]', 'Игровой сервер не подключён');
        put('[data-live-online]', 'Онлайн: —');
        put('[data-live-version]', 'Ожидаем heartbeat');
        put('[data-online-staff]', '—');
        put('[data-staff-connection]', 'Нет свежих данных');
        put('[data-staff-status]', 'Офлайн');
      } else {
        put('[data-live-status]', 'Игровой сервер на связи');
        put('[data-live-online]', `Онлайн: ${data.online} / ${data.max}`);
        put('[data-live-version]', `Minecraft ${data.version}`);
        put('[data-online-staff]', String(data.online));
        put('[data-staff-connection]', `Максимум: ${data.max}`);
        put('[data-staff-status]', 'На связи');
      }
    } catch (_) {
      put('[data-live-status]', 'Сайт работает без API');
      put('[data-live-online]', 'Онлайн: —');
      put('[data-live-version]', 'Нет подключения к backend');
      put('[data-staff-status]', 'Нет данных');
      put('[data-staff-connection]', 'API недоступен');
    }
  }
  async function loadNews() {
    const root = document.getElementById('live-news');
    if (!root) return;
    try {
      const result = await fetch('/api/news', { signal: AbortSignal.timeout(5000) });
      if (!result.ok) throw new Error('API unavailable');
      const { items } = await result.json();
      if (!Array.isArray(items)) throw new Error('Invalid response');
      root.replaceChildren();
      for (const item of items) {
        // textContent is intentional: no user-controlled HTML insertion.
        const article = document.createElement('article');
        article.className = 'news-item';
        const meta = document.createElement('small');
        meta.textContent = `${item.date} · ${item.tag}`;
        const title = document.createElement('h3');
        title.textContent = item.title;
        const body = document.createElement('p');
        body.textContent = item.body;
        article.append(meta, title, body);
        root.append(article);
      }
      if (items.length === 0) root.textContent = 'Записей пока нет.';
    } catch (_) {
      root.replaceChildren();
      const fallback = document.createElement('div');
      fallback.className = 'notice';
      fallback.textContent = 'Новости доступны только при запуске сайта через Node.js API.';
      root.append(fallback);
    }
  }
  async function loadMarket() {
    const root = document.getElementById('market-catalog');
    if (!root) return;
    try {
      const response = await fetch('/api/market', { cache: 'no-store', signal: AbortSignal.timeout(5000) });
      if (!response.ok) throw new Error('Catalog unavailable');
      const { offers } = await response.json();
      if (!Array.isArray(offers) || offers.length > 30) throw new Error('Invalid offers');
      root.replaceChildren();
      const header = document.createElement('div');
      header.className = 'market-row market-row-head';
      for (const heading of ['ТОВАР', 'ПОКУПКА', 'ПРОДАЖА']) {
        const cell = document.createElement('span'); cell.textContent = heading;
        header.append(cell);
      }
      root.append(header);
      for (const offer of offers) {
        if (typeof offer.id !== 'string' || typeof offer.name !== 'string' ||
            !Number.isSafeInteger(offer.buy) || !Number.isSafeInteger(offer.sell)) continue;
        const row = document.createElement('div'); row.className = 'market-row';
        const item = document.createElement('div');
        const name = document.createElement('strong'); name.textContent = offer.name;
        const code = document.createElement('small'); code.textContent = offer.id;
        item.append(name, code);
        const buy = document.createElement('b'); buy.textContent = `${offer.buy} монет`;
        const sell = document.createElement('b'); sell.textContent = `${offer.sell} монет`;
        row.append(item, buy, sell);
        root.append(row);
      }
    } catch (error) {
      root.replaceChildren();
      const fallback = document.createElement('div');
      fallback.className = 'notice';
      fallback.textContent = 'Не удалось получить каталог. Запусти сайт через Node.js API.';
      root.append(fallback);
    }
  }
  updateStatus();
  loadNews();
  loadMarket();
  setInterval(() => { if (!document.hidden) updateStatus(); }, 20_000);
})();

'use strict';
// 화면 동작. 데이터는 안드로이드 다리 window.A 에서 받음 (A.state() 등).
const $ = (s) => document.querySelector(s);
const $$ = (s) => [...document.querySelectorAll(s)];
const C = 697.4; // 원 둘레 (r=111)
let S = null;          // 마지막 상태
let mode = null;       // 화면에 그려진 모드 (false 보내는 폰, true 받는 폰)
const stack = [];      // 열린 페이지·창 (뒤로가기 순서)

const GB = 1024 ** 3, MB = 1024 ** 2;
function size(b) {
  if (!b) return '0';
  if (b >= GB) return (b / GB).toFixed(b >= 10 * GB ? 0 : 1) + 'GB';
  if (b >= MB) return Math.round(b / MB) + 'MB';
  return Math.max(1, Math.round(b / 1024)) + 'KB';
}
const num = (n) => n.toLocaleString('ko-KR');
const hm = (m) => String(Math.floor(m / 60)).padStart(2, '0') + ':' + String(m % 60).padStart(2, '0');
const win = (a) => (a[0] < 0 || a[0] === a[1]) ? '꺼짐' : hm(a[0]) + ' ~ ' + hm(a[1]);
const icon = (id, cls = 'ic') => `<svg class="${cls}"><use href="#i-${id}"/></svg>`;
const esc = (s) => String(s).replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
function setText(el, t) { if (el.textContent !== t) el.textContent = t; }

/* ---------- 탭 · 알약: 막대/판이 미끄러짐 ---------- */
function slideBar(nav) {
  const sel = nav.querySelector('button.sel'), bar = nav.querySelector('.bar');
  if (sel && bar) bar.style.transform = `translateX(${sel.offsetLeft}px) scaleX(${sel.offsetWidth})`;
}
function slidePlate(p) {
  const sel = p.querySelector('button.sel'), plate = p.querySelector('.plate');
  if (!sel || !plate) return;
  plate.style.width = sel.offsetWidth + 'px';
  plate.style.transform = `translateX(${sel.offsetLeft - 4}px)`;
}
function select(group, btn) {
  group.querySelectorAll('button').forEach((b) => b.classList.toggle('sel', b === btn));
  group.classList.contains('tabs') ? slideBar(group) : slidePlate(group);
}

/* ---------- 페이지 · 창 열고 닫기 ---------- */
function openPage(id) {
  const el = $('#' + id);
  if (id === 'recent') fillRecent();
  if (id === 'folders') fillFolders();
  if (id === 'settings') fillSettings();
  if (id === 'log') setText($('#logText'), A.logText() || '기록 없음');
  el.scrollTop = 0;
  el.classList.add('on');
  if (!stack.some((x) => x.classList.contains('page'))) $('#home').classList.add('under');
  stack.push(el);
  requestAnimationFrame(() => el.querySelectorAll('.tabs,.pills').forEach((g) => g.classList.contains('tabs') ? slideBar(g) : slidePlate(g)));
}
function openLayer(el) {
  $('#dim').classList.add('on');
  el.classList.add('on');
  stack.push(el);
}
function closeTop() {
  const el = stack.pop();
  if (!el) return false;
  el.classList.remove('on');
  if (!el.classList.contains('page')) {
    if (!stack.some((x) => !x.classList.contains('page'))) $('#dim').classList.remove('on');
  } else if (!stack.some((x) => x.classList.contains('page'))) $('#home').classList.remove('under');
  document.activeElement && document.activeElement.blur();
  return true;
}

/* ---------- 홈 ---------- */
function row({ icon: ic, color = '', label, val = '', go, act, time, last }) {
  const data = go ? `data-go="${go}"` : act ? `data-act="${act}"` : time ? `data-time="${time}"` : '';
  const chev = go || act || time ? icon('chev', 'chev') : '';
  return `<button class="row${last ? ' last' : ''}" ${data}>${icon(ic, 'ic ' + color)}<span class="label">${label}</span><span class="val">${val}</span>${chev}</button>`;
}
function homeRows() {
  const st = S.stats.today, q = S.quota;
  const qv = !q ? '-' : q.limit < 0 ? '무제한' : size(Math.max(0, q.limit - q.usage)) + ' 남음';
  const t = (k) => `${num(st[k].n)}개 · ${size(st[k].b)}`;
  const r = S.receiver ? [
    { icon: 'down', color: 'blue', label: '오늘 다운로드', val: t('down'), go: 'recent' },
    { icon: 'trash', color: 'red', label: '클라우드에서 삭제', val: t('delCloud'), go: 'recent' },
    { icon: 'cloud', label: '구글 계정', val: qv },
    { icon: 'hdd', label: '폰 남은 공간', val: size(S.phoneFree) },
    { icon: 'clock', label: '예약', val: win(S.down), time: 'down' },
  ] : [
    { icon: 'up', color: 'blue', label: '오늘 업로드', val: t('up'), go: 'recent' },
    { icon: 'trash', color: 'red', label: '장치에서 삭제', val: t('delLocal'), go: 'recent' },
    { icon: 'cloud', label: '구글 계정', val: qv },
    { icon: 'folder', label: '보낼 폴더', val: S.folders.length + '개', go: 'folders' },
    { icon: 'clock', label: '예약', val: win(S.up), time: 'up' },
  ];
  if (S.receiver && S.needStorage) r.unshift({ icon: 'folder', color: 'red', label: '저장소 권한', val: '허용하기', act: 'storage' });
  r[r.length - 1].last = true;
  return r.map(row).join('');
}
function renderHome() {
  const st = S.status, rows = $('#homeRows'), html = homeRows();
  if (rows._html !== html) { rows.innerHTML = html; rows._html = html; }

  let pct, big, small;
  if (st.running && st.totalBytes > 0) {
    pct = Math.round((1 - st.remainBytes / st.totalBytes) * 100);
    big = pct + '%';
    small = `${num(st.remainCount)}개 · ${size(st.remainBytes)} 남음`;
  } else if (S.waiting.n > 0) {
    pct = 0; big = num(S.waiting.n);
    small = `개 · ${size(S.waiting.b)} ${S.receiver ? '받을 것' : '보낼 것'}`;
  } else {
    pct = 100; big = null; small = S.receiver ? '모두 받음' : '모두 보냄';
  }
  $('#ringFill').style.strokeDashoffset = (C * (1 - pct / 100)).toFixed(1);
  const bigEl = $('#ringBig');
  if (big === null) { if (!bigEl.querySelector('svg')) bigEl.innerHTML = icon('check', ''); }
  else setText(bigEl, big);
  setText($('#ringSmall'), small);
  $('#ring').setAttribute('aria-label', big ? `${big} ${small}` : small);

  const chip = st.note || (st.running ? (S.receiver ? '받는 중' : '보내는 중') : '');
  $('#chip').classList.toggle('hide', !chip);
  if (chip) setText($('#chipText'), chip);

  const btn = !S.signedIn ? '구글 로그인' : S.receiver ? '지금 받기' : '지금 보내기';
  setText($('#mainBtn'), btn);
}
function render() {
  const first = mode === null;
  if (mode !== S.receiver) {
    const body = $('#homeBody');
    const tab = $(`#modeTabs button[data-mode="${S.receiver}"]`);
    select($('#modeTabs'), tab);
    if (first) { mode = S.receiver; renderHome(); }
    else {
      // 모드 바뀜: 지금 내용이 옆으로 빠지고 새 내용이 반대쪽에서 들어옴
      body.classList.add('out');
      setTimeout(() => {
        mode = S.receiver; renderHome();
        body.classList.remove('out'); body.classList.add('in');
        body.offsetWidth; body.classList.remove('in');
      }, 220);
    }
  } else renderHome();
  if (!S.receiver && !S.askedDelete && !$('#askDialog').classList.contains('on')) openLayer($('#askDialog'));
}
function refresh() {
  try { S = JSON.parse(A.state()); render(); } catch (e) { console.error(e); }
}

/* ---------- 최근 변경사항 ---------- */
let recentTab = 0, recentRange = 'today';
const RT = () => S.receiver ? [['down', '다운로드', 'down', 'blue'], ['delCloud', '클라우드에서 삭제', 'trash', 'red']]
                             : [['up', '업로드', 'up', 'blue'], ['delLocal', '장치에서 삭제', 'trash', 'red']];
function fillRecent() {
  const tabs = $('#recentTabs').querySelectorAll('button');
  RT().forEach((t, i) => { tabs[i].textContent = t[1]; tabs[i].classList.toggle('sel', i === recentTab); });
  $$('#rangePills button').forEach((b) => b.classList.toggle('sel', b.dataset.r === recentRange));
  const [type, , ic, color] = RT()[recentTab];
  const sum = S.stats[recentRange === 'today' ? 'today' : 'total'][type];
  setText($('#recentCount'), num(sum.n) + '개');
  setText($('#recentSize'), size(sum.b) + (recentRange === 'today' ? ' · 오늘' : ' · 전체'));
  const list = JSON.parse(A.recent(type, recentRange === 'today'));
  const d = (t) => { const x = new Date(t); return (recentRange === 'today' ? '' : `${x.getMonth() + 1}.${x.getDate()} `) + hm(x.getHours() * 60 + x.getMinutes()); };
  $('#recentList').innerHTML = list.length ? list.map((e, i) =>
    `<div class="row enter" style="animation-delay:${Math.min(i, 12) * 18}ms">${icon(ic, 'ic ' + color)}<span class="label">${esc(e.name)}</span><span class="time">${size(e.b)} · ${d(e.t)}</span></div>`).join('')
    : `<div class="row">${icon('check')}<span class="label">없음</span></div>`;
}

/* ---------- 보낼 폴더 · 하위 폴더 고르기 ---------- */
let subFor = null, subPick = null;
function fillFolders() {
  $('#folderList').innerHTML = S.folders.map((f) =>
    `<button class="row" data-uri="${esc(f.uri)}">${icon(f.label.startsWith('SD카드') ? 'sd' : 'phone')}<span class="two"><b>${esc(f.label)}</b><span>${icon('arrow')}PixelSync / ${esc(f.sub)}</span></span>${icon('chev', 'chev')}</button>`).join('');
}
function openSub(uri) {
  const f = S.folders.find((x) => x.uri === uri);
  subFor = uri; subPick = f.sub;
  setText($('#subFrom'), f.label);
  $('#newName').value = '';
  drawOpts([f.sub, S.defaultSub]);
  A.subs(); // 드라이브의 하위 폴더 목록은 app.on('subs')로 도착
  openLayer($('#subSheet'));
}
function drawOpts(names) {
  const uniq = [...new Set(names.filter(Boolean))];
  $('#subOpts').innerHTML = uniq.map((n) =>
    `<button class="opt${n === subPick ? ' sel' : ''}" data-sub="${esc(n)}">${icon('folder')}<span>${esc(n)}</span>${icon('check', 'ck')}</button>`).join('');
}

/* ---------- 설정 ---------- */
function fillSettings() {
  $$('[data-sw]').forEach((r) => r.classList.toggle('on', !!S[r.dataset.sw]));
  setText($('#vConcurrency'), S.concurrency + '개');
  setText($('#vMinFree'), S.minFreeGb + 'GB 남으면');
  setText($('#vUp'), win(S.up));
  setText($('#vDown'), win(S.down));
  setText($('#vAccount'), S.signedIn ? '연결됨' : '로그인');
}

/* ---------- 예약 · 고르기 창 ---------- */
let timeKey = null;
function openTime(key) {
  timeKey = key;
  const a = S[key];
  setText($('#timeTitle'), key === 'up' ? '예약 업로드' : '예약 다운로드');
  $('#tStart').value = hm(a[0] < 0 ? (key === 'up' ? 120 : 60) : a[0]);
  $('#tEnd').value = hm(a[0] < 0 ? (key === 'up' ? 360 : 420) : a[1]);
  openLayer($('#timeSheet'));
}
const toMin = (v) => { const [h, m] = v.split(':').map(Number); return h * 60 + m; };
let pickKey = null;
function openPick(key) {
  pickKey = key;
  const opts = key === 'concurrency' ? [1, 2, 3, 4, 5] : [5, 10, 20, 30];
  setText($('#pickTitle'), key === 'concurrency' ? '동시 전송' : '받기 중지 (남은 공간)');
  const p = $('#pickPills');
  p.innerHTML = opts.map((o) => `<button data-v="${o}" class="${o === S[key] ? 'sel' : ''}">${o}${key === 'concurrency' ? '개' : 'GB'}</button>`).join('') + '<span class="plate"></span>';
  openLayer($('#pickSheet'));
  requestAnimationFrame(() => slidePlate(p));
}

/* ---------- 누르기 ---------- */
document.addEventListener('click', (e) => {
  const t = e.target.closest('button');
  if (!t) return;
  const d = t.dataset;
  if (t.classList.contains('back')) return closeTop();
  if (d.mode !== undefined) { if (String(S.receiver) !== d.mode) { A.set('receiver', d.mode); select($('#modeTabs'), t); setTimeout(refresh, 50); } return; }
  if (d.go) return openPage(d.go);
  if (d.time) return openTime(d.time);
  if (d.pick) return openPick(d.pick);
  if (d.act) {
    A.act(d.act, '');
    if (d.act === 'copy') { setText($('#copyBtn'), '복사됨'); setTimeout(() => setText($('#copyBtn'), '복사'), 1200); }
    return;
  }
  if (d.sw) {
    const on = !t.classList.contains('on');
    t.classList.toggle('on', on);
    const sw = t.querySelector('.sw');
    sw.classList.remove('drop'); sw.offsetWidth; sw.classList.add('drop');
    A.set(d.sw, String(on)); S[d.sw] = on;
    return;
  }
  if (d.uri) return openSub(d.uri);
  if (d.sub) { subPick = d.sub; $$('#subOpts .opt').forEach((o) => o.classList.toggle('sel', o === t)); $('#newName').value = ''; return; }
  if (d.t) { recentTab = Number(d.t); fillRecent(); slideBar($('#recentTabs')); return; }
  if (d.r) { recentRange = d.r; fillRecent(); slidePlate($('#rangePills')); return; }
  if (d.v) { select($('#pickPills'), t); A.set(pickKey, d.v); S[pickKey] = Number(d.v); fillSettings(); setTimeout(closeTop, 250); return; }
  if (d.ask) { A.set('deleteAfter', d.ask); S.askedDelete = true; closeTop(); refresh(); return; }
  switch (t.id) {
    case 'mainBtn': A.act(S.signedIn ? 'syncNow' : 'signIn', ''); break;
    case 'saveSub': {
      const n = $('#newName').value.trim() || subPick;
      if (n) A.setSub(subFor, n);
      closeTop(); refresh(); fillFolders(); break;
    }
    case 'removeFolder': A.act('removeFolder', subFor); closeTop(); setTimeout(() => { refresh(); fillFolders(); }, 150); break;
    case 'timeSave': A.set(timeKey, toMin($('#tStart').value) + ',' + toMin($('#tEnd').value)); closeTop(); refresh(); fillSettings(); break;
    case 'timeOff': A.set(timeKey, '-1,-1'); closeTop(); refresh(); fillSettings(); break;
  }
});
$('#dim').addEventListener('click', () => { if (stack.length && stack[stack.length - 1].id !== 'askDialog') closeTop(); });
$('#newName').addEventListener('input', () => { if ($('#newName').value.trim()) $$('#subOpts .opt').forEach((o) => o.classList.remove('sel')); });

/* ---------- 안드로이드에서 부르는 것 ---------- */
window.app = {
  back: () => {
    if (stack.length && stack[stack.length - 1].id === 'askDialog') return true; // 처음 질문은 답해야 닫힘
    return closeTop();
  },
  on: (ev, data) => {
    if (ev === 'reload') { refresh(); if ($('#folders').classList.contains('on')) fillFolders(); }
    if (ev === 'subs' && subFor) drawOpts([subPick, S.defaultSub, ...S.folders.map((f) => f.sub), ...data]);
  },
};

refresh();
setInterval(refresh, 800); // 진행률 갱신 (메모리 값만 읽어서 가벼움)
window.addEventListener('resize', () => $$('.tabs,.pills').forEach((g) => g.classList.contains('tabs') ? slideBar(g) : slidePlate(g)));

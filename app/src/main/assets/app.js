'use strict';
// 화면 동작. 데이터는 안드로이드 다리 window.A 에서 받음 (A.state() 등).
const $ = (s) => document.querySelector(s);
const $$ = (s) => [...document.querySelectorAll(s)];
const C = 650.3; // 원 둘레 (r=103.5)
let S = null;          // 마지막 상태
let mode = null;       // 화면에 그려진 모드 (false 보내는 폰, true 받는 폰)
const stack = [];      // 열린 페이지·창 (뒤로가기 순서)

const GB = 1024 ** 3, MB = 1024 ** 2;
function size(b) {
  if (!b) return '0';
  if (b >= 1024 * GB) return (b / 1024 / GB).toFixed(1) + 'TB';
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
  const q = S.quota;
  const qv = !q ? '-' : q.limit < 0 ? '무제한' : size(Math.max(0, q.limit - q.usage)) + ' 남음';
  const r = S.receiver ? [
    { icon: 'cloud', label: '구글 계정', val: qv },
    { icon: 'hdd', label: '폰 남은 공간', val: size(S.phoneFree) },
    { icon: 'clock', label: '예약', val: win(S.down), time: 'down' },
  ] : [
    { icon: 'cloud', label: '구글 계정', val: qv },
    { icon: 'folder', label: '보낼 폴더', val: S.folders.length + '개', go: 'folders' },
    { icon: 'clock', label: '예약', val: win(S.up), time: 'up' },
  ];
  if (S.receiver && S.needStorage) r.unshift({ icon: 'folder', color: 'red', label: '저장소 권한', val: '허용하기', act: 'storage' });
  r[r.length - 1].last = true;
  // 구글 계정 줄 아래 사용량 막대
  const used = q && q.limit > 0 ? Math.min(100, q.usage / q.limit * 100).toFixed(1) : 0;
  return r.map((x) => row(x) + (x.label === '구글 계정' && q && q.limit > 0 ? `<div class="qbar"><i style="width:${used}%"></i></div>` : '')).join('');
}
function homeTiles() {
  const st = S.stats.today;
  const T = S.receiver ? [['down', 'blue', '오늘 다운로드', 'down'], ['trash', 'red', '클라우드 삭제', 'delCloud']]
                       : [['up', 'blue', '오늘 업로드', 'up'], ['trash', 'red', '장치에서 삭제', 'delLocal']];
  return T.map(([ic, c, l, k], i) => `<button class="tile" data-go="recent" data-tab="${i}"><span class="tl">${icon(ic, c)}${l}</span><span class="tv"><b>${num(st[k].n)}개</b> · ${size(st[k].b)}</span></button>`).join('');
}
function renderHome() {
  const st = S.status, rows = $('#homeRows'), html = homeRows(), tiles = $('#tiles'), th = homeTiles();
  if (rows._html !== html) { rows.innerHTML = html; rows._html = html; }
  if (tiles._html !== th) { tiles.innerHTML = th; tiles._html = th; }

  let pct, big, small, label;
  const sp = speed(st), what = S.receiver ? '받을 사진·영상' : '보낼 사진·영상';
  if (st.running && st.totalBytes > 0) {
    const left = Math.max(0, st.totalBytes - st.sentBytes);
    pct = Math.min(100, Math.floor(st.sentBytes / st.totalBytes * 100));
    label = `남음 ${num(st.remainCount)}개`; big = pct + '%';
    small = size(left) + (sp > 0 ? ' · ' + eta(left / sp) : '');
  } else if (S.waiting.n > 0) {
    pct = 0; label = what; big = num(S.waiting.n) + '개';
    small = st.busy ? '준비 중' : size(S.waiting.b);
  } else {
    const k = S.receiver ? 'down' : 'up', t = S.stats.today[k];
    pct = 100;
    if (t.n > 0) { label = '오늘'; big = num(t.n) + '개'; small = (S.receiver ? '모두 받음 · ' : '모두 보냄 · ') + size(t.b); }
    else { label = what; big = '0개'; small = S.receiver ? '모두 받음' : '모두 보냄'; }
  }
  setText($('#ringLabel'), label);
  const frac = st.running && st.totalBytes > 0 ? Math.min(1, st.sentBytes / st.totalBytes) : pct / 100;
  $('#ringFill').style.strokeDashoffset = (C * (1 - frac)).toFixed(1);
  setText($('#ringBig'), big);
  setText($('#ringSmall'), small);
  $('#ring').setAttribute('aria-label', `${label} ${big} ${small}`);

  // 단계 점 · 파일 막대 · 칩 · 버튼
  const prep = st.busy && st.step < 4, moving = st.busy && st.step === 4;
  $('#stepsBox').classList.toggle('open', prep && st.step >= 0);
  if (prep) drawSteps(st.step);
  $('#filesBox').classList.toggle('open', moving && st.items.length > 0);
  if (moving) drawFiles(st.items);

  const pressed = Date.now() - pressedAt < 4000 && !st.busy && !st.note.includes('대기');
  let chip = st.note, spin = prep || st.cancelled || pressed, ic = 'cloud';
  if (pressed) chip = '시작 중';
  else if (moving && !st.cancelled) { chip = (S.receiver ? '받는 중' : '보내는 중') + (sp > 0 ? ` · ${(sp / MB).toFixed(1)}MB/s` : ''); ic = S.receiver ? 'down' : 'up'; }
  else if (chip.startsWith('완료')) ic = 'check';
  else if (chip === '취소됨') ic = 'stop';
  else if (chip.startsWith('자동 꺼짐')) ic = 'clock';
  $('#chip').classList.toggle('hide', !chip);
  $('#chip').classList.toggle('muted', !spin && (ic === 'check' || ic === 'clock'));
  if (ic === 'check' && chip.startsWith('완료')) chip = '최근 ' + chip.replace(/^완료 · /, '');
  if (chip) {
    setText($('#chipText'), chip);
    const want = spin ? 'spin' : ic, c = $('#chipIc');
    if (c._ic !== want) { c._ic = want; c.innerHTML = `<use href="#i-${want}"/>`; c.classList.toggle('spin', spin); }
  }

  let btn, cls = 'primary', bic = '';
  if (!S.signedIn) btn = '구글 로그인';
  else if (st.cancelled && st.busy) { btn = '취소하는 중'; cls += ' soft'; bic = 'spin'; }
  else if (moving) { btn = '취소하기'; cls += ' stop'; bic = 'stop'; }
  else if (prep || pressed) { btn = '시작 중'; cls += ' soft'; bic = 'spin'; }
  else btn = S.receiver ? '지금 받기' : '지금 보내기';
  const mb = $('#mainBtn'), key = cls + btn;
  if (mb._key !== key) {
    mb._key = key; mb.className = cls;
    mb.innerHTML = (bic ? `<svg class="${bic === 'spin' ? 'spin' : ''}"><use href="#i-${bic}"/></svg>` : '') + `<span>${btn}</span>`;
  }
  mb.dataset.state = !S.signedIn ? 'login' : moving && !st.cancelled ? 'cancel' : (st.busy || pressed) ? 'wait' : 'go';
}
let pressedAt = 0;

// 실시간 속도: 최근 6초 동안 올린(받은) 바이트 ÷ 시간. 동시 전송 전체 합계.
const samples = [];
function speed(st) {
  if (!st.running) { samples.length = 0; return 0; }
  const now = Date.now(), last = samples[samples.length - 1];
  if (last && (st.sentBytes < last.b || st.totalBytes !== last.total)) samples.length = 0; // 새로 시작함
  samples.push({ t: now, b: st.sentBytes, total: st.totalBytes });
  while (samples.length > 2 && now - samples[0].t > 6000) samples.shift();
  const a = samples[0], dt = (now - a.t) / 1000;
  return dt >= 2 ? (st.sentBytes - a.b) / dt : 0;
}
function eta(sec) {
  if (!isFinite(sec)) return '';
  const m = Math.round(sec / 60);
  if (m < 1) return '1분 안';
  if (m < 60) return `약 ${m}분`;
  const h = Math.floor(m / 60), r = m % 60;
  return `약 ${h}시간` + (r ? ` ${r}분` : '');
}
const STEPS = () => S.receiver ? ['로그인', '접속', '목록', '공간', '받기'] : ['로그인', '접속', '폴더', '용량', '보내기'];
function drawSteps(n) {
  const box = $('#steps'), names = STEPS().join();
  if (box._names !== names) {
    box._names = names;
    box.innerHTML = STEPS().map((t, i) => `<div class="st"><span class="dot"><i></i><svg><use href="#i-check"/></svg></span>${i < 4 ? '<span class="ln"><b></b></span>' : ''}<span>${t}</span></div>`).join('');
  }
  box.querySelectorAll('.st').forEach((e, i) => { e.classList.toggle('done', i < n); e.classList.toggle('cur', i === n); });
}
function drawFiles(items) {
  const box = $('#files'), names = items.slice(0, 3).map((x) => x.name);
  if (box._names !== names.join('|')) {
    box._names = names.join('|');
    box.innerHTML = names.map((n) => `<div class="f">${icon(/\.(mp4|mov|3gp|m4v|mkv|webm|avi|mpe?g|wmv)$/i.test(n) ? 'play' : 'image')}<div class="w"><div class="t"><span>${esc(n)}</span><b>0%</b></div><div class="bar"><i></i></div></div></div>`).join('');
  }
  box.querySelectorAll('.f').forEach((f, i) => {
    const it = items[i], p = it.size > 0 ? it.done / it.size : 0, chk = it.size > 0 && it.done >= it.size;
    f.classList.toggle('chk', chk);
    const b = f.querySelector('b'), html = chk ? '확인 중' : `<span class="sz">${size(it.done)} / ${size(it.size)} · </span>${Math.floor(p * 100)}%`;
    if (b._h !== html) { b._h = html; b.innerHTML = html; }
    f.querySelector('.bar i').style.transform = `scaleX(${p})`;
  });
}
function render() {
  const first = mode === null;
  if (mode !== S.receiver) {
    const body = $('#homeBody');
    const tab = $(`#modeSeg button[data-mode="${S.receiver}"]`);
    select($('#modeSeg'), tab);
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
let wasBusy = false, askedBattery = false;
function refresh() {
  try {
    S = JSON.parse(A.state());
    if (wasBusy && !S.status.busy) A.act('reload', ''); // 끝나면 남은 개수 다시 세기
    wasBusy = S.status.busy;
    render();
    // 처음 질문 뒤 한 번: 배터리 최적화 제외 요청 (앱을 내려도 안 끊기게)
    if ((S.askedDelete || S.receiver) && !S.batteryOk && !S.askedBattery && !askedBattery) { askedBattery = true; A.act('batteryOnce', ''); }
  } catch (e) { console.error(e); }
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
  setText($('#autoLabel'), S.receiver ? '자동 받기' : '자동 보내기');
  setText($('#vBattery'), S.batteryOk ? '허용됨' : '허용하기');
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
  if (d.mode !== undefined) { if (String(S.receiver) !== d.mode) { A.set('receiver', d.mode); select($('#modeSeg'), t); setTimeout(refresh, 50); } return; }
  if (d.go) { if (d.tab) recentTab = Number(d.tab); return openPage(d.go); }
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
    if (d.sw === 'auto') setTimeout(refresh, 50);
    return;
  }
  if (d.uri) return openSub(d.uri);
  if (d.sub) { subPick = d.sub; $$('#subOpts .opt').forEach((o) => o.classList.toggle('sel', o === t)); $('#newName').value = ''; return; }
  if (d.t) { recentTab = Number(d.t); fillRecent(); slideBar($('#recentTabs')); return; }
  if (d.r) { recentRange = d.r; fillRecent(); slidePlate($('#rangePills')); return; }
  if (d.v) { select($('#pickPills'), t); A.set(pickKey, d.v); S[pickKey] = Number(d.v); fillSettings(); setTimeout(closeTop, 250); return; }
  if (d.ask) { A.set('deleteAfter', d.ask); S.askedDelete = true; closeTop(); refresh(); return; }
  switch (t.id) {
    case 'mainBtn': {
      const st = t.dataset.state;
      if (st === 'login') A.act('signIn', '');
      else if (st === 'cancel') { setText($('#cancelTitle'), S.receiver ? '받기를 취소할까요?' : '보내기를 취소할까요?'); openLayer($('#cancelDialog')); }
      else if (st === 'go') { pressedAt = Date.now(); A.act('syncNow', ''); renderHome(); }
      break;
    }
    case 'keepGoing': closeTop(); break;
    case 'doCancel': A.act('cancel', ''); closeTop(); setTimeout(refresh, 50); break;
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

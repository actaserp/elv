/**
 * AI 고장분석 공통 스크립트 (웹·모바일 공용)
 *
 * - AiFault.post / get     : 로딩 오버레이 없이 조용히 호출 (입력 중 추천처럼 자주 부르는 요청용)
 * - AiFault.log            : 사용 기록 (/api/ai/log) — 실패해도 화면에 영향 없음
 * - AiFault.attachClassify : 고장 상세내용 입력 → 고장내용 추천 배지
 *
 * AI 기능은 보조 수단이다. 실패·지연 시 아무것도 보여주지 않고 기존 입력 흐름을 그대로 둔다.
 */
(function (window, $) {
  'use strict';

  // common.js 의 withCtx 와 동일 (컨텍스트 경로 붙이기)
  const ctx = function (url) {
    return typeof window.withCtx === 'function' ? window.withCtx(url) : url;
  };

  const csrf = function () {
    return $('[name=_csrf]').val() || $('meta[name=_csrf]').attr('content') || '';
  };

  function esc(s) {
    return String(s == null ? '' : s)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
  }

  function post(url, data, ok, fail, timeoutMs) {
    const payload = Object.assign({}, data || {});
    payload._csrf = csrf();
    return $.ajax({
      url: ctx(url), type: 'POST', dataType: 'json', data: payload, global: false,
      timeout: timeoutMs || 0,
      headers: {'X-CSRF-TOKEN': payload._csrf},
      success: function (res) {
        if (res && res.success !== false) { ok && ok(res.data, res); } else { fail && fail(res && res.message); }
      },
      error: function (xhr, status) { fail && fail(status === 'timeout' ? 'timeout' : 'error'); }
    });
  }

  function get(url, data, ok, fail, timeoutMs) {
    return $.ajax({
      url: ctx(url), type: 'GET', dataType: 'json', data: data || {}, global: false,
      timeout: timeoutMs || 0,
      success: function (res) {
        if (res && res.success !== false) { ok && ok(res.data, res); } else { fail && fail(res && res.message); }
      },
      error: function (xhr, status) { fail && fail(status === 'timeout' ? 'timeout' : 'error'); }
    });
  }

  function log(values) {
    try { post('/api/ai/log', values); } catch (e) { /* 기록 실패는 무시 */ }
  }

  function injectStyle() {
    if (document.getElementById('aiFaultStyle')) return;
    const css = `
      .ai-badge-wrap { display:flex; flex-wrap:wrap; gap:6px; margin-top:6px; }
      .ai-badge { display:inline-flex; align-items:center; gap:4px; padding:4px 10px; border-radius:14px;
                  font-size:12px; line-height:1.4; cursor:pointer; border:1px solid transparent; user-select:none;
                  white-space:nowrap; }
      .ai-badge b { font-weight:700; }
      .ai-badge .ai-tag { font-size:10px; font-weight:700; letter-spacing:.3px; opacity:.85; }
      .ai-badge.high { background:#e7f6ee; color:#17794a; border-color:#9fd9b9; }
      .ai-badge.mid  { background:#fff3e6; color:#b25b00; border-color:#f3c48f; }
      .ai-badge.applied { box-shadow:0 0 0 2px currentColor inset; }
      .ai-badge:hover { filter:brightness(0.97); }
    `;
    $('<style id="aiFaultStyle">').text(css).appendTo(document.head);
  }

  /**
   * 고장내용 추천 배지
   *
   * opts = {
   *   text:    상세내용 입력 요소 (selector|element)
   *   memo:    (선택) 함께 보낼 메모 입력 요소
   *   host:    배지를 넣을 요소 (고장내용 입력칸 아래/옆)
   *   source:  'WEB' | 'MOBILE'
   *   apply:   function(code, name) — 배지를 눌렀을 때 고장내용 칸에 넣는 함수
   *   current: function() → 현재 선택된 고장내용 코드
   * }
   * 반환: { refresh(), reset(), saveLog(finalCode, refKey, text) }
   */
  function attachClassify(opts) {
    injectStyle();
    const $text = $(opts.text);
    const $memo = opts.memo ? $(opts.memo) : null;
    const $host = $(opts.host);
    const $wrap = $('<div class="ai-badge-wrap" style="display:none"></div>').appendTo($host);

    let timer = null;
    let seq = 0;
    let lastQuery = null;
    let shown = null;      // 화면에 보여준 1순위 추천 {code,name,confidence}
    let applied = null;    // 배지를 눌러 적용한 코드

    function query() {
      return (($text.val() || '') + ' ' + ($memo ? ($memo.val() || '') : '')).trim();
    }

    function render(data) {
      $wrap.empty();
      shown = null;
      const threshold = Number(data && data.threshold) || 60;
      const list = ((data && data.candidates) || []).filter(c => c.confidence >= threshold);
      if (list.length === 0) { $wrap.hide(); return; }
      shown = list[0];
      list.forEach(function (c) {
        const level = c.confidence >= 80 ? 'high' : 'mid';
        const basis = c.basis === 'KEYWORD'
          ? '키워드 "' + c.keyword + '"'
          : '과거 유사 접수 ' + c.neighbors + '건 (최고 유사도 ' + c.similarity + '%)';
        const $b = $('<span class="ai-badge ' + level + '"></span>')
          .attr('title', 'AI 추천 근거: ' + basis + '\n누르면 고장내용에 적용됩니다.')
          .html('<span class="ai-tag">AI 추천</span> <b>' + esc(c.name) + '</b> ' + c.confidence + '%')
          .on('click', function () {
            if (applied === c.code) return;
            applied = c.code;
            opts.apply(c.code, c.name);
            $wrap.find('.ai-badge').removeClass('applied');
            $b.addClass('applied');
          });
        if (applied === c.code) $b.addClass('applied');
        $wrap.append($b);
      });
      $wrap.show();
    }

    function refresh() {
      const q = query();
      if (q.replace(/\s/g, '').length < 2) { lastQuery = q; render(null); return; }
      if (q === lastQuery) return;
      lastQuery = q;
      const my = ++seq;
      post('/api/ai/classify/suggest', {text: $text.val() || '', memo: $memo ? ($memo.val() || '') : ''},
        function (data) { if (my === seq) render(data); },
        function () { if (my === seq) render(null); },   // 실패 시 조용히 숨김
        5000);
    }

    $text.on('input', function () {
      clearTimeout(timer);
      timer = setTimeout(refresh, 300);
    }).on('blur', function () {
      clearTimeout(timer);
      refresh();
    });
    if ($memo) $memo.on('blur', refresh);

    return {
      refresh: function () { lastQuery = null; refresh(); },
      reset: function () { clearTimeout(timer); lastQuery = null; applied = null; render(null); },
      /** 저장 성공 뒤 호출 — 추천을 보여줬는지, 최종 코드가 추천과 같은지 기록 */
      saveLog: function (finalCode, refKey) {
        log({
          source: opts.source, feature: 'CLASSIFY', event: 'SAVE',
          query_text: query(), ref_key: refKey || '',
          suggested: shown ? shown.code : '', confidence: shown ? shown.confidence : '',
          final_value: finalCode || '',
          adopted: shown ? String(shown.code === finalCode) : '',
          detail: applied ? 'badge_clicked=' + applied : ''
        });
      }
    };
  }

  window.AiFault = {post: post, get: get, log: log, esc: esc, attachClassify: attachClassify};
})(window, jQuery);

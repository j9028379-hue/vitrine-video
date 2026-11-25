(function () {
  "use strict";

  const hasBridge = () => typeof window.VitrineAndroid !== "undefined" && window.VitrineAndroid !== null;
  const hasNativeGuideAudio = () =>
    hasBridge() && typeof window.VitrineAndroid.guideAudioPrepare === "function";

  const guideAudioState = {
    assetPath: "",
    durationSec: 0,
    currentSec: 0,
    playing: false,
    ended: false,
    ready: false,
    error: ""
  };
  let guideAudioListener = null;
  let activeTemplateFormat = "t1";

  function applyGuideAudioSnapshot(raw) {
    if (!raw || typeof raw !== "object") return;
    guideAudioState.assetPath = raw.assetPath || "";
    guideAudioState.durationSec = Number(raw.durationSec) || 0;
    guideAudioState.currentSec = Number(raw.currentSec) || 0;
    guideAudioState.playing = Boolean(raw.playing);
    guideAudioState.ended = Boolean(raw.ended);
    guideAudioState.ready = Boolean(raw.ready);
    guideAudioState.error = raw.error || "";
    if (typeof guideAudioListener === "function") {
      try {
        guideAudioListener({ ...guideAudioState });
      } catch (_) {}
    }
  }

  window.__vitrineNativeGuideAudio = {
    isSupported: () => hasNativeGuideAudio(),
    getState: () => ({ ...guideAudioState }),
    bind: (assetSrc, listener) => {
      guideAudioListener = typeof listener === "function" ? listener : null;
      if (!hasNativeGuideAudio()) return;
      guideAudioState.durationSec = 0;
      guideAudioState.currentSec = 0;
      guideAudioState.playing = false;
      guideAudioState.ended = false;
      guideAudioState.ready = false;
      guideAudioState.error = "";
      window.VitrineAndroid.guideAudioPrepare(String(assetSrc || ""));
    },
    play: () => {
      if (hasNativeGuideAudio()) window.VitrineAndroid.guideAudioPlay();
    },
    pause: () => {
      if (hasNativeGuideAudio()) window.VitrineAndroid.guideAudioPause();
    },
    seekTo: (sec) => {
      if (hasNativeGuideAudio()) {
        const ms = Math.max(0, Math.round(Number(sec || 0) * 1000));
        window.VitrineAndroid.guideAudioSeekTo(ms);
      }
    },
    seekBy: (deltaSec) => {
      if (hasNativeGuideAudio()) {
        const ms = Math.round(Number(deltaSec || 0) * 1000);
        window.VitrineAndroid.guideAudioSeekBy(ms);
      }
    },
    stop: () => {
      guideAudioListener = null;
      guideAudioState.playing = false;
      guideAudioState.currentSec = 0;
      guideAudioState.ended = false;
      if (hasNativeGuideAudio()) window.VitrineAndroid.guideAudioStop();
    }
  };

  function stopAllGuideAudio() {
    const audio = document.getElementById("guide-audio");
    if (audio) {
      audio.pause();
      audio.currentTime = 0;
    }
    if (window.__vitrineNativeGuideAudio) {
      window.__vitrineNativeGuideAudio.stop();
    }
  }

  function enforceFluidAndroidViewport() {
    const holder = document.getElementById("holder");
    const device = document.getElementById("device");
    if (holder) {
      holder.style.width = "100%";
      holder.style.height = "100%";
      holder.style.removeProperty("--r");
    }
    if (device) {
      device.style.transform = "none";
      device.style.width = "100%";
      device.style.height = "100%";
    }
  }

  function getCurrentLang() {
    const flow = window.__vitrineFlow && window.__vitrineFlow.getState();
    return (flow && flow.lang) || document.documentElement.lang || "fr";
  }

  function getActiveScreenId() {
    const active = document.querySelector(".screen.is-active");
    if (active && active.id) return active.id;
    const flow = window.__vitrineFlow && window.__vitrineFlow.getState();
    return (flow && flow.screenId) || "s-splash";
  }

  function notifyActiveScreen() {
    if (hasBridge() && typeof window.VitrineAndroid.onScreenChanged === "function") {
      window.VitrineAndroid.onScreenChanged(getActiveScreenId());
    }
  }

  function observeActiveScreenChanges() {
    const screens = document.querySelectorAll(".screen");
    if (!screens.length || typeof MutationObserver === "undefined") return;
    const obs = new MutationObserver(() => notifyActiveScreen());
    screens.forEach((sc) => {
      obs.observe(sc, { attributes: true, attributeFilter: ["class"] });
    });
    notifyActiveScreen();
  }

  function updateComingSoonBadges() {
    const fr = getCurrentLang() === "fr";
    const label = fr ? "Bientôt" : "Soon";
    ["beaute", "resto"].forEach((s) => {
      const card = document.querySelector(`.card[data-sector="${s}"]`);
      if (card) {
        card.setAttribute("data-coming-soon", "true");
        card.setAttribute("data-soon-label", label);
      }
    });
    ["t2"].forEach((f) => {
      const card = document.querySelector(`.format-card[data-format="${f}"]`);
      if (card) {
        card.setAttribute("data-coming-soon", "true");
        card.setAttribute("data-soon-label", label);
      }
    });
  }

  function showToast(id, msg) {
    const toast = document.getElementById(id);
    if (!toast) return;
    toast.textContent = msg;
    toast.classList.add("is-visible");
    clearTimeout(toast._timer);
    toast._timer = setTimeout(() => toast.classList.remove("is-visible"), 2600);
  }

  function collectFormResponses() {
    const val = (id) => {
      const el = document.getElementById(id);
      return el ? String(el.value) : "";
    };
    if (activeTemplateFormat === "t4") return {
      commerce: val("q-nom"),
      produit: val("q-produit"),
      habituel: val("q-prix"),
      promo: val("q-promo"),
      fin: val("q-fin"),
      localisation: val("q-quartier"),
      whatsapp: val("q-whatsapp")
    };
    return {
      nom: val("q-nom"),
      produit: val("q-produit"),
      prix: val("q-prix"),
      demonstration: val("q-accroche"),
      quartier: val("q-quartier"),
      whatsapp: val("q-whatsapp")
    };
  }

  function clearFormInputs() {
    document.querySelectorAll("#s-questions input").forEach((input) => {
      input.value = "";
      const field = input.closest(".field");
      if (field) field.classList.remove("is-error");
    });
  }

  function applyResponsesToDom(resp) {
    if (!resp || Object.keys(resp).length === 0) {
      return;
    }
    const setVal = (id, v) => {
      const el = document.getElementById(id);
      if (el && typeof v === "string") {
        el.value = v;
      }
    };
    setVal("q-nom", resp.nom || resp.commerce || "");
    setVal("q-produit", resp.produit || "");
    setVal("q-prix", resp.prix || resp.habituel || "");
    setVal("q-promo", resp.promo || "");
    setVal("q-fin", resp.fin || "");
    setVal("q-accroche", resp.accroche || resp.demonstration || "");
    setVal("q-quartier", resp.quartier || resp.localisation || "");
    setVal("q-whatsapp", resp.whatsapp || "");
  }

  function syncGuideBadge() {
    const flow = window.__vitrineFlow && window.__vitrineFlow.getState();
    if (!flow || flow.screenId !== "s-capture" || flow.stage !== "guide") return;
    const main = document.querySelector("#capture-root .guide-readable-main");
    const footer = document.querySelector("#capture-root .guide-readable-footer");
    if (!main) return;
    const existing = main.querySelector(".vitrine-scene-saved-pill");
    if (existing) existing.remove();
    const existingJump = footer && footer.querySelector("#vitrine-jump-montage");
    if (existingJump) existingJump.remove();

    if (hasBridge() && window.VitrineAndroid.hasValidatedScene(flow.scene)) {
      const fr = getCurrentLang() === "fr";
      const pill = document.createElement("div");
      pill.className = "vitrine-scene-saved-pill";
      pill.textContent = fr
        ? `✓ Scène ${flow.scene + 1} déjà filmée et validée`
        : `✓ Scene ${flow.scene + 1} already filmed & approved`;
      const header = main.querySelector(".guide-readable-header");
      if (header && header.nextSibling) {
        main.insertBefore(pill, header.nextSibling);
      }
    }

    if (footer && hasBridge() && window.VitrineAndroid.isAllScenesValidated()) {
      const fr = getCurrentLang() === "fr";
      const jumpBtn = document.createElement("button");
      jumpBtn.id = "vitrine-jump-montage";
      jumpBtn.type = "button";
      jumpBtn.className = "guide-readable-start";
      jumpBtn.style.marginTop = "8px";
      jumpBtn.style.background = "#14265D";
      jumpBtn.textContent = fr
        ? "LANCER LE MONTAGE (4 SCÈNES PRÊTES) →"
        : "START EDITING (4 SCENES READY) →";
      jumpBtn.addEventListener("click", () => {
        stopAllGuideAudio();
        window.VitrineAndroid.launchMontage();
      });
      footer.appendChild(jumpBtn);
    }
  }

  function initAndroidIntegration() {
    enforceFluidAndroidViewport();
    window.addEventListener("resize", () => requestAnimationFrame(enforceFluidAndroidViewport));
    window.addEventListener("orientationchange", () => setTimeout(enforceFluidAndroidViewport, 150));
    if (window.visualViewport) {
      window.visualViewport.addEventListener("resize", () => requestAnimationFrame(enforceFluidAndroidViewport));
    }

    updateComingSoonBadges();
    observeActiveScreenChanges();

    // Scroll focused form input comfortably into view above soft keyboard
    document.querySelectorAll("#s-questions input").forEach((input) => {
      input.addEventListener("focus", () => {
        setTimeout(() => {
          input.scrollIntoView({ behavior: "smooth", block: "center" });
        }, 180);
      });
    });

    // Sync language changes to Android native overlays
    document.querySelectorAll(".lang-card").forEach((card) => {
      card.addEventListener("click", () => {
        const lang = card.dataset.lang || "fr";
        updateComingSoonBadges();
        if (hasBridge()) window.VitrineAndroid.setLanguage(lang);
      });
    });

    const langNext = document.getElementById("lang-next");
    if (langNext) {
      langNext.addEventListener("click", () => {
        if (hasBridge()) window.VitrineAndroid.setLanguage(getCurrentLang());
      });
    }

    // Enforce T3-only operational scope on #s-sector (preserve mockup visual cards)
    document.querySelectorAll("#s-sector .card").forEach((card) => {
      card.addEventListener(
        "click",
        (e) => {
          const sector = card.dataset.sector;
          if (sector !== "commerce") {
            e.preventDefault();
            e.stopImmediatePropagation();
            const fr = getCurrentLang() === "fr";
            showToast(
              "sector-toast",
              fr
                ? "Seul Commerce → Démonstration (COMMERCE-T3) est actif dans cette version."
                : "Only General Retail → Demonstration (COMMERCE-T3) is active in this version."
            );
          }
        },
        true
      );
    });

    // Keep unsupported templates gated; T4 is backed by its verified package.
    const formatsWrap = document.querySelector("#s-formats .formats");
    if (formatsWrap) {
      formatsWrap.addEventListener(
        "click",
        (e) => {
          const card = e.target.closest(".format-card");
          if (!card) return;
          const fmt = card.dataset.format || card.id.replace(/^format-/, "");
          if (fmt !== "t1" && fmt !== "t3" && fmt !== "t4") {
            e.preventDefault();
            e.stopImmediatePropagation();
            const fr = getCurrentLang() === "fr";
            showToast(
              "format-toast",
              fr
                ? "Ce gabarit sera ajouté prochainement. Choisis « Présentation de mon commerce » ou « Démonstration »."
                : "This template will be added soon. Choose 'Business presentation' or 'Demonstration'."
            );
            return;
          }
          activeTemplateFormat = fmt;
          if (hasBridge()) {
            window.VitrineAndroid.onTemplateSelected(fmt === "t1" ? "COMMERCE-T1" : fmt === "t4" ? "COMMERCE-T4" : "COMMERCE-T3");
            try {
              const saved = JSON.parse(window.VitrineAndroid.getSavedResponsesJson() || "{}");
              if (Object.keys(saved).length > 0) {
                setTimeout(() => applyResponsesToDom(saved), 0);
              } else {
                setTimeout(clearFormInputs, 0);
              }
            } catch (_) {}
          }
        },
        true
      );
    }

    // Forward merchant-entered answer text to Android when continuing from #s-questions
    const qNext = document.getElementById("questions-next");
    if (qNext) {
      qNext.addEventListener(
        "click",
        () => {
          // Persist the exact answers on every continue attempt, even if a browser-only
          // constraint rejects one field. The mockup owns navigation validation; package
          // and engine validation remain authoritative for whether rendering can start.
          if (hasBridge()) {
            window.VitrineAndroid.saveResponses(JSON.stringify(collectFormResponses()));
          }
        },
        true
      );
    }

    // Intercept simulated camera launch & review navigation inside #capture-root
    const captureRoot = document.getElementById("capture-root");
    if (captureRoot) {
      captureRoot.addEventListener(
        "click",
        (e) => {
          const startBtn = e.target.closest("#flow-start");
          if (startBtn) {
            e.preventDefault();
            e.stopImmediatePropagation();
            stopAllGuideAudio();
            const flow = window.__vitrineFlow && window.__vitrineFlow.getState();
            const sceneIdx = flow ? flow.scene : 0;
            if (hasBridge()) {
              window.VitrineAndroid.openSceneCamera(sceneIdx);
            }
            return;
          }

          const backBtn = e.target.closest("#flow-back");
          if (backBtn) {
            const flow = window.__vitrineFlow && window.__vitrineFlow.getState();
            if (flow && flow.stage === "guide" && flow.scene > 0 && hasBridge()) {
              if (window.VitrineAndroid.hasValidatedScene(flow.scene - 1)) {
                e.preventDefault();
                e.stopImmediatePropagation();
                stopAllGuideAudio();
                window.VitrineAndroid.openSceneReview(flow.scene - 1);
              }
            }
          }
        },
        true
      );

      const observer = new MutationObserver(() => syncGuideBadge());
      observer.observe(captureRoot, { childList: true });
    }

    // Restore saved responses on startup ONLY if an unfinished session exists
    if (hasBridge()) {
      try {
        const saved = JSON.parse(window.VitrineAndroid.getSavedResponsesJson() || "{}");
        if (Object.keys(saved).length > 0) {
          applyResponsesToDom(saved);
        } else {
          clearFormInputs();
        }
      } catch (_) {}
      window.VitrineAndroid.onWebReady();
    }
  }

  window.VitrineNativeCallback = {
    applySystemInsets: function (topPx, rightPx, bottomPx, leftPx, kbBottomPx) {
      const rootStyle = document.documentElement.style;
      rootStyle.setProperty("--safe-top", Math.max(0, Number(topPx) || 0) + "px");
      rootStyle.setProperty("--safe-right", Math.max(0, Number(rightPx) || 0) + "px");
      rootStyle.setProperty("--safe-bottom", Math.max(0, Number(bottomPx) || 0) + "px");
      rootStyle.setProperty("--safe-left", Math.max(0, Number(leftPx) || 0) + "px");
      rootStyle.setProperty("--kb-bottom", Math.max(0, Number(kbBottomPx) || 0) + "px");
      enforceFluidAndroidViewport();
    },
    goToSceneGuide: function (sceneIdx) {
      stopAllGuideAudio();
      if (window.__vitrineFlow) {
        window.__vitrineFlow.setSceneStage(sceneIdx, "guide");
        syncGuideBadge();
      }
      notifyActiveScreen();
    },
    goToQuestions: function () {
      if (window.__vitrineFlow) {
        window.__vitrineFlow.selectT3();
        window.__vitrineFlow.goTo("s-questions", true);
        if (hasBridge()) {
          try {
            const saved = JSON.parse(window.VitrineAndroid.getSavedResponsesJson() || "{}");
            if (Object.keys(saved).length > 0) {
              applyResponsesToDom(saved);
            } else {
              clearFormInputs();
            }
          } catch (_) {}
        }
      }
      notifyActiveScreen();
    },
    goToIntro: function () {
      if (window.__vitrineFlow) {
        window.__vitrineFlow.goTo("s-intro", true);
      }
      notifyActiveScreen();
    },
    onGuideAudioState: function (jsonStr) {
      try {
        const parsed = typeof jsonStr === "string" ? JSON.parse(jsonStr) : jsonStr;
        applyGuideAudioSnapshot(parsed);
      } catch (_) {}
    },
    resetSessionToBlank: function (targetScreenId) {
      clearFormInputs();
      const audio = document.getElementById("guide-audio");
      if (audio) {
        audio.pause();
        audio.currentTime = 0;
      }
      if (window.__vitrineNativeGuideAudio) {
        window.__vitrineNativeGuideAudio.stop();
      }
      document.querySelectorAll(".vitrine-scene-saved-pill, #vitrine-jump-montage").forEach((el) => el.remove());
      if (window.__vitrineFlow && typeof window.__vitrineFlow.resetToBlank === "function") {
        window.__vitrineFlow.resetToBlank(targetScreenId || "s-intro");
      } else if (window.__vitrineFlow) {
        window.__vitrineFlow.goTo(targetScreenId || "s-intro", true);
      }
      notifyActiveScreen();
    },
    restoreResponses: function (jsonStr) {
      try {
        const parsed = JSON.parse(jsonStr);
        if (parsed && Object.keys(parsed).length > 0) {
          applyResponsesToDom(parsed);
        } else {
          clearFormInputs();
        }
      } catch (_) {}
    },
    handleAndroidBack: function () {
      const flow = window.__vitrineFlow && window.__vitrineFlow.getState();
      const screenId = flow ? flow.screenId : "s-splash";
      if (screenId === "s-capture") {
        const backBtn = document.getElementById("flow-back");
        if (backBtn) {
          backBtn.click();
          return "HANDLED";
        }
      } else if (screenId === "s-guide-intro") {
        const btn = document.getElementById("guide-intro-back");
        if (btn) {
          btn.click();
          return "HANDLED";
        }
      } else if (screenId === "s-questions") {
        const btn = document.getElementById("questions-back");
        if (btn) {
          btn.click();
          return "HANDLED";
        }
      } else if (screenId === "s-formats") {
        const btn = document.getElementById("formats-back");
        if (btn) {
          btn.click();
          return "HANDLED";
        }
      } else if (screenId === "s-sector") {
        const btn = document.getElementById("sector-back");
        if (btn) {
          btn.click();
          return "HANDLED";
        }
      } else if (screenId === "s-intro") {
        if (window.__vitrineFlow) {
          window.__vitrineFlow.goTo("s-lang", true);
          return "HANDLED";
        }
      }
      return "EXIT_APP";
    }
  };

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", initAndroidIntegration);
  } else {
    initAndroidIntegration();
  }
})();

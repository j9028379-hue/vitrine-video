(function () {
  "use strict";

  const hasBridge = () => typeof window.VitrineAndroid !== "undefined" && window.VitrineAndroid !== null;

  function getCurrentLang() {
    const flow = window.__vitrineFlow && window.__vitrineFlow.getState();
    return (flow && flow.lang) || document.documentElement.lang || "fr";
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
    ["t1", "t2", "t4"].forEach((f) => {
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
      return el ? String(el.value || "").trim() : "";
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

  function applyResponsesToDom(resp) {
    if (!resp) return;
    const setVal = (id, v) => {
      const el = document.getElementById(id);
      if (el && typeof v === "string" && v.length > 0) {
        el.value = v;
      }
    };
    setVal("q-nom", resp.nom);
    setVal("q-produit", resp.produit);
    setVal("q-prix", resp.prix);
    setVal("q-accroche", resp.demonstration);
    setVal("q-quartier", resp.quartier);
    setVal("q-whatsapp", resp.whatsapp);
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
        const audio = document.getElementById("guide-audio");
        if (audio) {
          audio.pause();
          audio.currentTime = 0;
        }
        window.VitrineAndroid.launchMontage();
      });
      footer.appendChild(jumpBtn);
    }
  }

  function initAndroidIntegration() {
    updateComingSoonBadges();

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

    // Enforce T3-only operational scope on #s-formats
    const formatsWrap = document.querySelector("#s-formats .formats");
    if (formatsWrap) {
      formatsWrap.addEventListener(
        "click",
        (e) => {
          const card = e.target.closest(".format-card");
          if (!card) return;
          const fmt = card.dataset.format || card.id.replace(/^format-/, "");
          if (fmt !== "t3") {
            e.preventDefault();
            e.stopImmediatePropagation();
            const fr = getCurrentLang() === "fr";
            showToast(
              "format-toast",
              fr
                ? "Ce gabarit sera ajouté prochainement. Choisis « Démonstration » (COMMERCE-T3)."
                : "This template will be added soon. Choose 'Demonstration' (COMMERCE-T3)."
            );
            return;
          }
          if (hasBridge()) {
            window.VitrineAndroid.onTemplateSelected("COMMERCE-T3");
            try {
              const saved = JSON.parse(window.VitrineAndroid.getSavedResponsesJson() || "{}");
              setTimeout(() => applyResponsesToDom(saved), 0);
            } catch (_) {}
          }
        },
        true
      );
    }

    // Forward validated merchant answers to Android engine when continuing from #s-questions
    const qNext = document.getElementById("questions-next");
    if (qNext) {
      qNext.addEventListener(
        "click",
        () => {
          // Wait one microtask after app.js validation runs; only save if all required fields pass
          const inputs = Array.from(document.querySelectorAll("#s-questions input"));
          const anyMissing = inputs.some((i) => {
            const field = i.closest(".field");
            return field && !field.hidden && i.required && !i.value.trim();
          });
          if (!anyMissing && hasBridge()) {
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
            const audio = document.getElementById("guide-audio");
            if (audio) {
              audio.pause();
              audio.currentTime = 0;
            }
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
                const audio = document.getElementById("guide-audio");
                if (audio) {
                  audio.pause();
                  audio.currentTime = 0;
                }
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

    // Restore saved responses on startup if checkpoint exists
    if (hasBridge()) {
      try {
        const saved = JSON.parse(window.VitrineAndroid.getSavedResponsesJson() || "{}");
        applyResponsesToDom(saved);
      } catch (_) {}
      window.VitrineAndroid.onWebReady();
    }
  }

  window.VitrineNativeCallback = {
    goToSceneGuide: function (sceneIdx) {
      const audio = document.getElementById("guide-audio");
      if (audio) {
        audio.pause();
        audio.currentTime = 0;
      }
      if (window.__vitrineFlow) {
        window.__vitrineFlow.setSceneStage(sceneIdx, "guide");
        syncGuideBadge();
      }
    },
    goToQuestions: function () {
      if (window.__vitrineFlow) {
        window.__vitrineFlow.selectT3();
        window.__vitrineFlow.goTo("s-questions", true);
        if (hasBridge()) {
          try {
            const saved = JSON.parse(window.VitrineAndroid.getSavedResponsesJson() || "{}");
            applyResponsesToDom(saved);
          } catch (_) {}
        }
      }
    },
    goToIntro: function () {
      if (window.__vitrineFlow) {
        window.__vitrineFlow.goTo("s-intro", true);
      }
    },
    restoreResponses: function (jsonStr) {
      try {
        applyResponsesToDom(JSON.parse(jsonStr));
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

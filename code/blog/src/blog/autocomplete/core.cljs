(ns blog.autocomplete.core
  (:require-macros
    [cljs.core.async.macros :refer [go]]
    [blog.utils.macros :refer [dochan]])
  (:require
    [goog.userAgent :as ua]
    [clojure.string :as string]
    [cljs.core.async :refer [>! <! alts! chan sliding-buffer]]
    [blog.responsive.core :as resp]
    [blog.utils.dom :as dom]
    [blog.utils.helpers :as h]
    [blog.utils.reactive :as r]))

(def base-url
  "http://en.wikipedia.org/w/api.php?action=opensearch&format=json&search=")

;; -----------------------------------------------------------------------------
;; Interface representation protocols

(defprotocol IHideable
  (-hide! [view])
  (-show! [view]))

(defprotocol ITextField
  (-set-text! [field txt])
  (-text [field]))

(defprotocol IUIList
  (-set-items! [list items]))

;; =============================================================================
;; Autocompleter

(defn menu-proc [select cancel menu data]
  (let [ctrl (chan)
        sel  (->> (resp/selector
                    (resp/highlighter select menu ctrl)
                    menu data)
               (r/filter vector?)
               (r/map second))]
    (go (let [[v sc] (alts! [cancel sel])]
          (do (>! ctrl :exit)
            (if (= sc cancel)
              ::cancel
              v))))))

(defn autocompleter* [{:keys [focus query select cancel menu] :as opts}]
  (let [out (chan)
        [query raw] (r/split #(r/throttle-msg? %) query)]
    (go (loop [items nil focused false]
          (let [[v sc] (alts! [raw cancel focus query select])]
            (cond
              (= sc focus)
              (recur items true)

              (= sc cancel)
              (do (-hide! menu)
                (recur items (not= v :blur)))

              (and focused (= sc query))
              (let [[v c] (alts! [cancel ((:completions opts) (second v))])]
                (if (= c cancel)
                  (do (-hide! menu)
                    (recur nil (not= v :blur)))
                  (do (-show! menu)
                    (-set-items! menu v)
                    (recur v focused))))

              (= sc select)
              (let [choice (<! ((:menu-proc opts) (r/concat [v] select)
                                 (r/fan-in [raw cancel]) menu items))]
                  (-hide! menu)
                  (if (= choice ::cancel)
                    (recur nil (not= v :blur))
                    (do (-set-text! (:input opts) choice)
                      (>! out choice)
                      (recur nil focused))))

              :else
              (recur items focused)))))
    out))

;; =============================================================================
;; HTML Specific Code (aka Quarantine Line)

(extend-type js/HTMLInputElement
  ITextField
  (-set-text! [field text]
    (set! (.-value field) text))
  (-text [field]
    (.-value field)))

(extend-type js/HTMLUListElement
  IHideable
  (-hide! [list]
    (dom/add-class! list "hidden"))
  (-show! [list]
    (dom/remove-class! list "hidden"))

  IUIList
  (-set-items! [list items]
    (->> (for [item items] (str "<li>" item "</li>"))
      (apply str)
      (dom/set-html! list))))

(defn menu-item-event [menu type]
  (->> (r/listen menu type
         (fn [e]
           (when (dom/in? e menu)
             (.preventDefault e)))
         (chan (sliding-buffer 1)))
    (r/map
      (fn [e]
        (let [li (dom/parent (.-target e) "li")]
          (h/index-of (dom/by-tag-name menu "li") li))))))

;; TODO: add IE hack for blur

(defn html-menu-events [input menu]
  (r/fan-in
    [(->> (r/listen input :keydown)
       (r/map resp/key-event->keycode)
       (r/filter resp/KEYS)
       (r/map resp/key->keyword))
     (r/hover-child menu "li")
     (->> (r/cyclic-barrier
            [(menu-item-event menu :mousedown)
             (menu-item-event menu :mouseup)])
       (r/filter (fn [[d u]] (= d u)))
       (r/always :select))]))

(defn relevant-keys [kc]
  (or (= kc 8)
      (and (> kc 46)
           (not (#{91 92 93} kc)))))

(defn html-input-events [input]
  (->> (r/listen input :keydown)
    (r/map resp/key-event->keycode)
    (r/filter relevant-keys)
    (r/map #(-text input))
    (r/split #(not (string/blank? %)))))

;; NOTE: in IE we want to ignore blur somehow, in IE we should
;; filter out blur

(defn html-autocompleter [input menu completions throttle]
  (let [[filtered removed] (html-input-events input)]
    (autocompleter*
      {:focus  (r/always :focus (r/listen input :focus))
       :query  (r/throttle* (r/distinct filtered) throttle)
       :select (html-menu-events input menu)
       :cancel (r/fan-in [removed (r/always :blur (r/listen input :blur))])
       :input  input
       :menu   menu
       :menu-proc   menu-proc
       :completions completions})))

;; =============================================================================
;; Example

(defn wikipedia-search [query]
  (go (nth (<! (r/jsonp (str base-url query))) 1)))

(let [ac (html-autocompleter
           (dom/by-id "autocomplete")
           (dom/by-id "autocomplete-menu")
           wikipedia-search 750)]
  (go (while true (<! ac))))

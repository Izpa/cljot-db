(ns telegram.dialogue.keyboard
  (:require
   [integrant.core :as ig]
   [telegrambot-lib.core :as tbot]
   [taoensso.timbre :as log]
   [clojure.string :as str]))

(defn make-file-row [files]
  (mapv (fn [{:keys [id name]}]
          [{:text name :callback_data (str "file:" id)}])
        files))

(defn make-pagination-row [page total-pages]
  (cond
    (and (= page 1) (= total-pages 1)) []
    (= page 1) [[{:text "▶" :callback_data (str "page:" (inc page))}]]
    (= page total-pages) [[{:text "◀" :callback_data (str "page:" (dec page))}]]
    :else [[{:text "◀" :callback_data (str "page:" (dec page))}
            {:text "▶" :callback_data (str "page:" (inc page))}]]))

(defn make-keyboard [files page total-pages]
  {:inline_keyboard (vec (concat (make-file-row files)
                                 (make-pagination-row page total-pages)))})

(defmethod ig/init-key ::main-keyboard
  [_ {:keys [list-files total-pages user-states]}]
  (fn [{:keys [answer user-id]}]
    (let [page     1
          files    (list-files page)
          pages    (total-pages)
          keyboard (make-keyboard files page pages)
          msg      (answer "Выберите видео:" {:reply_markup keyboard}) ;; tbot/send-message должен вернуть {:message_id ...}
          msg-id   (-> msg :result :message_id)]
      (log/info "---message: " msg)
      (swap! user-states assoc-in [user-id :interface-messages] [msg-id]))))

(defmethod ig/init-key ::handle-callback-query
  [_ {:keys [bot select-file list-files total-pages user-states]}]
  (fn [callback]
    (let [data     (:data callback)
          msg      (:message callback)
          chat-id  (get-in callback [:message :chat :id])
          msg-id   (:message_id msg)
          uid      (get-in callback [:from :id])]

      (log/info "Callback query data:" data)

      (cond
        ;; Обработка выбора видео
        (str/starts-with? data "file:")
        (let [file-id     (subs data 5)
              {:keys [original-file-id name]} (select-file file-id)
              files       (list-files 1)
              pages       (total-pages)
              keyboard    (make-keyboard files 1 pages)]

          ;; удаляем старые сообщения (интерфейс и видео)
          (doseq [mid (get-in @user-states [uid :interface-messages])]
            (tbot/delete-message bot chat-id mid))

          ;; отправляем новое видео
          (let [video-msg (tbot/send-video bot chat-id original-file-id {:caption name})
                video-id  (-> video-msg :result :message_id)
                kb-msg    (tbot/send-message bot chat-id "Выберите видео:" {:reply_markup keyboard})
                kb-id     (-> kb-msg :result :message_id)]

            ;; сохраняем новые сообщения (интерфейс и видео)
            (swap! user-states assoc-in [uid :interface-messages] [kb-id video-id])))

        ;; Переход между страницами
        (str/starts-with? data "page:")
        (let [page      (parse-long (subs data 5))
              files     (list-files page)
              pages     (total-pages)
              keyboard  (make-keyboard files page pages)]
          (tbot/edit-message-reply-markup bot chat-id msg-id
                                          {:inline_keyboard (:inline_keyboard keyboard)}))))))


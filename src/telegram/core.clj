(ns telegram.core
  (:require
   [integrant.core :as ig]
   [telegram.long-polling :refer [long-polling]]
   [taoensso.timbre :as log]
   [org.httpkit.client :as http]
   [telegrambot-lib.core :as tbot]
   [utils :refer [pformat]]))

(defmethod ig/init-key ::token [_ token]
  token)

(defmethod ig/init-key ::msg-handler [_ {:keys [process-msg]}]
  (fn [{:keys [message callback_query] :as upd}]
    (if-let [msg (or message (-> callback_query
                                 :message
                                 (assoc :data (:data callback_query))))]
      (do (when (-> msg
                    :chat
                    :id
                    not)
            (log/warn "strange message without chat-id: " (pformat upd)))
          (log/info "Received message")
          (log/info (pformat msg))
          (try (process-msg msg)
               (catch Exception e
                 (log/error "Catch exception " e))))
      (log/error "unexpected message type" (pformat upd)))))

(defmethod ig/halt-key! ::run-bot [_ {:keys [bot thread]}]
  (if thread
    (do (log/info "Stop long-polling telegram-bot")
        (.interrupt ^Thread thread))
    (do (log/info "Stop webhook telegram-bot")
        (tbot/delete-webhook bot))))

(defmethod ig/init-key ::run-bot [_ {:keys [bot
                                               url
                                               long-polling-config
                                               msg-handler]}]
  (log/info "Start telegram bot: " (or url long-polling-config))
  (merge
   {:bot bot}
   (if (nil? url)
     {:thread (long-polling bot long-polling-config msg-handler)}
     {:webhook (tbot/set-webhook bot {:url url
                                      :content-type :multipart})})))

(defmethod ig/init-key ::bot [_ {:keys [token]}]
  (log/info "Start bot")
  (if (nil? token)
    (log/error "No bot token")
    (let [bot (tbot/create token)]
      (log/info (tbot/get-me bot))
      bot)))

(defmethod ig/init-key ::download-file [_ {:keys [token bot]}]
  (fn [file-id callback]
    (let [file-info (tbot/get-file bot file-id)
          file-path (get-in file-info [:result :file_path])
          url (str "https://api.telegram.org/file/bot" token "/" file-path)]
      (http/get url {:as :stream}
                (fn [{:keys [status body error]}]
                  (if (and (= status 200) body)
                    (callback body file-path)
                    (throw (ex-info "Failed to fetch Telegram file"
                                    {:status status :error error}))))))))

(defn send-message
  ([bot to-id main-content] (send-message bot to-id main-content {}))
  ([bot to-id main-content additional-content]
   (let [sent_message (tbot/send-message bot
                                         to-id
                                         main-content
                                         (merge {:parse_mode "HTML"}
                                                additional-content))]
     (log/info "Send message: "
               (pformat sent_message))
     sent_message)))

(defmethod ig/init-key ::send-message [_ {:keys [bot]}]
  (partial send-message bot))


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

(defmethod ig/init-key ::msg-handler [_ {:keys [process-msg callback-handler]}]
  (fn [{:keys [message callback_query] :as upd}]
    (cond
      message
      (try
        (log/info "Received message")
        (log/info (pformat message))
        (process-msg message)
        (catch Exception e
          (log/error "Catch exception " e)))

      callback_query
      (try
        (log/info "Received callback")
        (log/info (pformat callback_query))
        (callback-handler callback_query)
        (catch Exception e
          (log/error "Catch exception in callback " e)))

      :else
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
    (let [file-info (tbot/get-file bot file-id)]
      (log/info "Download tg file file-info " file-info)
      (if-let [file-path (get-in file-info [:result :file_path])]
        (let [url (str "https://api.telegram.org/file/bot" token "/" file-path)]
          (http/get url {:as :stream}
                    (fn [{:keys [status body error]}]
                      (if (and (= status 200) body)
                        (callback {:ok? true :body body :file-path file-path})
                        (callback {:ok? false :error (or error {:status status})})))))
        (callback {:ok? false
                   :error {:status :no-file-path
                           :description (get file-info :description "Unknown error")}})))))

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


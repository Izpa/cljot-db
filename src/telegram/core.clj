(ns telegram.core
  (:require
   [integrant.core :as ig]
   [telegram.long-polling :refer [long-polling]]
   [taoensso.timbre :as log]
   [org.httpkit.client :as http]
   [telegrambot-lib.core :as tbot]
   [clojure.string :as str]
   [utils :refer [pformat]]))

(defmethod ig/init-key ::token [_ token]
  token)

(defn val+file-tg-type->file-data
  ([val tg-type]
   {:type :file
    :val (assoc val :tg-type tg-type)})
  ([val tg-type caption]
   (assoc-in (val+file-tg-type->file-data val tg-type) [:val :caption] caption)))

(defn text->data [text]
  (if (str/starts-with? text "/")
        (let [[cmd & args] (-> text (subs 1) (str/split #"\s+"))]
          {:type :command
           :val {:command (keyword cmd)
                 :args args}})
        {:type :text
         :val text}))

(defn data->type+val [data]
  (let [text (or (:data data)
                 (:text data))]
    (cond
      text (text->data text)
      (:document data) (val+file-tg-type->file-data (:document data) :document)
      (:video data) (val+file-tg-type->file-data (:video data) :video)
      (:video_note data) (val+file-tg-type->file-data (:video_note data) :video_note)
      (:voice data) (val+file-tg-type->file-data (:voice data) :voice)
      (:audio data) (val+file-tg-type->file-data (:audio data) :audio)
      (:photo data) (val+file-tg-type->file-data (:photo data) :photo)
      :else nil)))

(defn normalize-upd [upd]
  (let [clean-upd (or (:callback_query upd)
                      upd)]
    (-> (or (:forward_from_message clean-upd)
            clean-upd)
        data->type+val
        (merge {:user (:from clean-upd)
                :chat (:chat clean-upd)
                :original-upd upd
                :message-id (:message_id clean-upd)}))))

(defmethod ig/init-key ::upd-handler [_ {:keys [process-msg]}]
  (fn [upd]
    (let [normalized-upd (try
                           (normalize-upd upd)
                           (catch Exception e
                             (log/error "Can't normalize upd: " upd)
                             (log/error "Exception: " e)))]
      (try
        (process-msg normalized-upd)
        (catch Exception e
          (log/error "Can't process normalized upd: " normalized-upd)
          (log/error "Exception: " e))))))

(defmethod ig/halt-key! ::run-bot [_ {:keys [bot thread]}]
  (if thread
    (do (log/info "Stop long-polling telegram-bot")
        (.interrupt ^Thread thread))
    (do (log/info "Stop webhook telegram-bot")
        (tbot/delete-webhook bot))))

(defmethod ig/init-key ::run-bot [_ {:keys [bot
                                            url
                                            long-polling-config
                                            upd-handler]}]
  (log/info "Start telegram bot: " (or url long-polling-config))
  (merge
   {:bot bot}
   (if (nil? url)
     {:thread (long-polling bot long-polling-config upd-handler)}
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

(defn send-message!
  ([bot to-id main-content] (send-message! bot to-id main-content {}))
  ([bot to-id main-content additional-content]
   (let [sent_message (tbot/send-message bot
                                         to-id
                                         main-content
                                         (merge {:parse_mode "HTML"}
                                                additional-content))]
     (log/info "Send message: "
               (pformat sent_message))
     sent_message)))

(defmethod ig/init-key ::send-message! [_ {:keys [bot]}]
  (partial send-message! bot))

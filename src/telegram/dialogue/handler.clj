(ns telegram.dialogue.handler
  (:require
   [integrant.core :as ig]
   [taoensso.timbre :as log]
   [telegrambot-lib.core :as tbot]
   [telegram.dialogue.msg-helpers :as msg-helpers]
   [clojure.string :as str])
  (:import (java.util UUID)))

(defmethod ig/init-key ::default-upload [_ _]
  (fn [{:keys [answer admin?]}]
    (if admin?
      (answer "Введите имя видео:")
      (answer "Команда недоступна."))))

(defmethod ig/init-key ::default-read [_ {:keys [bot select-file download-file]}]
  (fn [{:keys [answer msg user-id]}]
    (let [[_ file-id] (str/split (:text msg) #"\s+")]
      (if-not file-id
        (answer "Укажите ID файла после /read")
        (try
          (let [{:keys [original-chat-id original-message-id storage_key name is-circle]}
                (select-file file-id)]
            (try
              (tbot/copy-message bot user-id original-chat-id original-message-id)
              (catch Exception e
                (log/warn e "Copy failed, falling back to download from MinIO")
                (let [input-stream (download-file storage_key)
                      file {:content input-stream
                            :filename (or name "video.mp4")}]
                  (if is-circle
                    (tbot/send-video-note bot user-id file)
                    (tbot/send-video bot user-id file {:caption name}))))))
          (catch Exception e
            (log/warn e "Файл не найден или не доступен")
            (answer "Файл не найден или больше не доступен.")))))))

(defmethod ig/init-key ::default-on-text [_ _]
  (fn [{:keys [answer msg]}]
    (log/infof "Default state got on-text: %s" (:text msg))
    (answer msg-helpers/default-error-message)))

(defmethod ig/init-key ::awaiting-video-name-on-text [_ {:keys [user-states]}]
  (fn [{:keys [answer user-id msg]}]
    (log/infof "Processing video name input: %s" (:text msg))
    (swap! user-states update user-id assoc
           :state-name :awaiting-video-file
           :state-data {:file-name (:text msg)})
    (answer "Теперь отправьте само видео или кружок, можно пересланное.")))

(defmethod ig/init-key ::awaiting-video-file-on-file
  [_ {:keys [user-states upload-file! insert-file! download-file]}]
  (fn [{:keys [answer user-id msg]}]
    (let [file-id (msg-helpers/extract-file-id msg)
          file-name (get-in @user-states [user-id :state-data :file-name])
          is-circle (msg-helpers/circle? msg)]
      (if file-id
        (download-file file-id
                       (fn [{:keys [ok? body file-path error]}]
                         (if ok?
                           (let [ext (second (re-find #"\.([^.]+)$" file-path))
                                 storage-key (str (UUID/randomUUID)
                                                  (when ext (str "." ext)))]
                             (log/info "Upload file: " (upload-file! storage-key body))
                             (log/info "Saving to DB with: "
                                       {:name file-name :key storage-key :is-circle is-circle})
                             (insert-file! {:original-chat-id user-id
                                            :original-message-id (:message_id msg)
                                            :original-file-id file-id
                                            :storage-key storage-key
                                            :name file-name
                                            :is-circle is-circle})
                             (swap! user-states update-in [user-id :state-data] dissoc :file-name)
                             (answer "Видео загружено."))
                           (do
                             (log/warn "Download failed: " error)
                             (insert-file! {:original-chat-id user-id
                                            :original-message-id (:message_id msg)
                                            :original-file-id file-id
                                            :name file-name
                                            :is-circle is-circle})
                             (answer (str "Файл слишком большой, его сохранность не гарантируется. "
                                      "Рекомендуется дополнительно сохранить его вручную."))))))
        (answer "Ожидалось видео или кружок.")))))


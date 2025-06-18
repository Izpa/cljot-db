(ns telegram.dialogue.files
  (:require
   [integrant.core :as ig]
   [taoensso.timbre :as log]
   [telegrambot-lib.core :as tbot])
  (:import
   (java.util
    UUID)))

(defmethod ig/init-key ::read [_ {:keys [bot
                                         main-keyboard
                                         select-file
                                         download-file
                                         user-id->delete-keyboard-msg-id!
                                         user-id->delete-video-msg-id!
                                         set-user-video-msg-id!]}]
  (fn [upd]
    (log/info "in :read")
    (let [user-id (-> upd :user :id)
          file-id (-> upd :val :args first)
          main-keyboard (partial main-keyboard upd)]
      (user-id->delete-video-msg-id! user-id)
      (user-id->delete-keyboard-msg-id! user-id)
      (if-not file-id
        (main-keyboard "Ошибка при загрузке файла (не указан file-id), попробуйте другое видео")
        (try
          (let [{:keys [original-chat-id original-message-id storage_key name is-circle]}
                (select-file file-id)

                msg
                (try
                  (tbot/copy-message bot user-id original-chat-id original-message-id)
                  (catch Exception e
                    (log/warn e "Copy failed, falling back to download from MinIO")
                    (let [input-stream (download-file storage_key)
                          file {:content input-stream
                                :filename (or name "video.mp4")}]
                      (if is-circle
                        (tbot/send-video-note bot user-id file)
                        (tbot/send-video bot user-id file {:caption name})))))]
            (->> msg
                 :result
                 :message_id
                 (set-user-video-msg-id! user-id))
            (main-keyboard))
          (catch Exception e
            (log/warn e "Файл не найден или не доступен")
            (main-keyboard "Ошибка: файл не найден или больше не доступен, попробуйте другое видео")))))))

(defmethod ig/init-key ::upload [_ send-message!]
  (fn [upd]
    (log/info "in :upload")
    (-> upd
        :user
        :id
        (send-message! "Введите имя видео: "))))

(defmethod ig/init-key ::video-name [_ {:keys [send-message!
                                               set-user-video-name!]}]
  (log/info "in :video-name")
  (fn [upd]
    (let [user-id (-> upd :user :id)]
      (->> upd
           :val
           (set-user-video-name! user-id))
      (send-message! user-id "Теперь отправьте само видео или кружок, можно пересланное."))))

(defmethod ig/init-key ::video-file
  [_ {:keys [send-message!
             upload-file!
             insert-file!
             download-file
             user-id->video-name
             user-id->clear-video-name!
             new-keyboard]}]
  (fn [upd]
    (log/info "in :video-file")
    (let [user-id (-> upd :user :id)
          file (:val upd)
          file-id (:file_id file)
          file-name (user-id->video-name user-id)
          is-circle (= (:tg-type file) :video_note)
          answer (partial send-message! user-id)]
      (if file-id
        (download-file file-id
                       (fn [{:keys [ok? body file-path error]}]
                         (let [base {:original-chat-id user-id
                                     :original-message-id (:message-id upd)
                                     :original-file-id file-id
                                     :name file-name
                                     :is-circle is-circle}]
                           (if ok?
                             (let [ext (second (re-find #"\.([^.]+)$" file-path))
                                   storage-key (str (UUID/randomUUID)
                                                    (when ext (str "." ext)))]
                               (log/info "Upload file: " (upload-file! storage-key body))
                               (log/info "Saving to DB with: " (assoc base :storage-key storage-key))
                               (insert-file! (assoc base :storage-key storage-key))
                               (user-id->clear-video-name! user-id)
                               (answer "Видео загружено."))
                             (do
                               (log/warn "Download failed: " error)
                               (log/info "Saving to DB with: " base)
                               (insert-file! base)
                               (answer (str "Файл слишком большой, его сохранность не гарантируется. "
                                            "Рекомендуется дополнительно сохранить его вручную."))))
                           (new-keyboard upd))))
        (answer "Ожидалось видео или кружок.")))))

(defmethod ig/init-key ::rename-file [_ {:keys [send-message! set-user-rename-file-id!]}]
  (fn [upd]
    (log/info "in :rename-file")
    (let [user-id (-> upd :user :id)
          file-id (-> upd :val :args first)]
      (if file-id
        (do (set-user-rename-file-id! user-id file-id)
            (send-message! user-id "Введите новое имя файла:"))
        (send-message! user-id "Не указан ID файла для переименования.")))))

(defmethod ig/init-key ::renaming-file
  [_ {:keys [send-message!
             update-file-name!
             user-id->rename-file-id
             user-id->clear-rename-file-id!]}]
  (fn [upd]
    (log/info "in :renaming-file")
    (let [user-id (-> upd :user :id)
          new-name (-> upd :val :text)
          file-id (user-id->rename-file-id user-id)]
      (if (and file-id new-name)
        (do
          (update-file-name! file-id new-name)
          (send-message! user-id "Имя файла обновлено."))
        (send-message! user-id "Ошибка: не удалось обновить имя файла."))
      (user-id->clear-rename-file-id! user-id))))

(defmethod ig/init-key ::rename-file [_ {:keys [send-message! set-user-rename-file-id!]}]
  (fn [upd]
    (log/info "in :rename-file")
    (let [user-id (-> upd :user :id)
          file-id (-> upd :val :args first)]
      (if file-id
        (do (set-user-rename-file-id! user-id file-id)
            (send-message! user-id "Введите новое имя файла:"))
        (send-message! user-id "Не указан ID файла для переименования.")))))

(defmethod ig/init-key ::delete-file [_ send-message!]
  (fn [upd]
    (log/info "in :delete-file")
    (let [user-id (-> upd :user :id)
          file-id (-> upd :val :args first)]
      (if file-id
        (send-message! user-id
                       "Вы уверены, что хотите удалить файл?"
                       {:inline_keyboard [[{:text "Да" :callback_data (str "/deleting " file-id)}
                                           {:text "Отмена" :callback_data "/start "}]]})
        (send-message! user-id "Не указан ID файла для удаления.")))))

(defmethod ig/init-key ::deleting-file
  [_ {:keys [send-message!
             delete-file!]}]
  (fn [upd]
    (log/info "in :deleting-file")
    (let [user-id (-> upd :user :id)
          file-id (-> upd :val :args first)]
      (if file-id
        (do
          (delete-file! file-id)
          (send-message! user-id "Файл удалён"))
        (send-message! user-id "Ошибка: не удалось обновить имя файла.")))))

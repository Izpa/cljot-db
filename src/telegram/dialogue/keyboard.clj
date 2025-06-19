(ns telegram.dialogue.keyboard
  (:require
   [integrant.core :as ig]
   [taoensso.timbre :as log]
   [telegrambot-lib.core :as tbot]))

(defn admin-keyboard [file-id]
  {:inline_keyboard [[{:text "rename" :callback_data (str "/rename " file-id)}
                      {:text "delete" :callback_data (str "/delete " file-id)}]]})

(defmethod ig/init-key ::send-admin-keyboard! [_  {:keys [send-message!
                                                          add-user-tmp-msg-ids!]}]
  (fn [user-id file-name file-id]
    (log/info "Send admin keyboard!")
    (->> file-id
         admin-keyboard
         (assoc {} :reply_markup)
         (send-message! user-id (str "Действия с файлом " file-name))
         :result
         :message_id
         (add-user-tmp-msg-ids! user-id))))

(defn make-file-row
  [files]
  (mapv (fn [{:keys [id name]}]
          [{:text name :callback_data (str "/read " id)}])
        files))

(defn make-pagination-row
  [page total-pages]
  (cond
    (and (= page 1) (= total-pages 1)) []
    (= page 1) [[{:text "▶" :callback_data (str "/page " (inc page))}]]
    (= page total-pages) [[{:text "◀" :callback_data (str "/page " (dec page))}]]
    :else [[{:text "◀" :callback_data (str "/page " (dec page))}
            {:text "▶" :callback_data (str "/page " (inc page))}]]))

(defn make-keyboard
  [add-files-actions? files page total-pages]
  {:inline_keyboard (vec (concat (make-file-row files)
                                 (make-pagination-row page total-pages)
                                 (when add-files-actions?
                                   [[{:text "+" :callback_data "/upload"}]])))})

(defmethod ig/init-key ::user-id->delete-and-clear-tmp-msg! [_ {:keys [bot user-id->tmp-msg-ids user-id->clear-tmp-msg-ids!]}]
  (fn [user-id]
    (when-let [msg-ids (user-id->tmp-msg-ids user-id)]
      (doseq [msg-id msg-ids]
        (log/info "Delete message. user-id: " user-id "; msg-id: " msg-id)
        (log/info "delete message: " (tbot/delete-message bot user-id msg-id)))
      (user-id->clear-tmp-msg-ids! user-id))))

(defmethod ig/init-key ::upd->delete-and-clear-tmp-msg! [_ user-id->delete-and-clear-tmp-msg!]
  (fn [upd]
    (-> upd
        :user
        :id
        user-id->delete-and-clear-tmp-msg!)))

(defmethod ig/init-key ::main-keyboard
  [_ {:keys [send-message!
             list-files
             total-pages
             user-id->page
             user-id->clear-critical-tmp-data
             set-user-keyboard-msg-id!]}]
  (fn main-keyboard
    ([upd] (main-keyboard upd "Bыберите видео:"))
    ([upd msg]
     (log/info "in :main-keyboard")
     (let [user-id  (-> upd :user :id)
           pages (total-pages)
           page (min pages (user-id->page user-id))]
       (user-id->clear-critical-tmp-data user-id)
       (->> pages
            (make-keyboard (= :admin (get-in upd [:user :role])) (list-files page) page)
            (assoc {} :reply_markup)
            (send-message! user-id (str "Page " page "/" pages " " msg))
            :result
            :message_id
            (set-user-keyboard-msg-id! user-id))))))

(defmethod ig/init-key ::page [_ {:keys [bot
                                         user-id->keyboard-msg-id
                                         list-files
                                         total-pages
                                         user-id+page->update-page!]}]
  (fn [upd]
    (log/info "in :page")
    (let [user-id  (-> upd :user :id)
          page     (-> upd :val :args first parse-long)
          files    (list-files page)
          pages    (total-pages)
          msg-id   (user-id->keyboard-msg-id user-id)
          keyboard (make-keyboard (= :admin (get-in upd [:user :role])) files page pages)]
      (log/info "Edit message text: "
                (tbot/edit-message-text bot
                                        user-id
                                        msg-id (str "Page " page "/" pages " Выберите видео:")))
      (log/info "Edit message keyboard: "
                (tbot/edit-message-reply-markup bot
                                                user-id
                                                msg-id
                                                keyboard))
      (user-id+page->update-page! user-id page))))

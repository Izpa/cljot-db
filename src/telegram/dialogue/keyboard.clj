(ns telegram.dialogue.keyboard
  (:require
   [integrant.core :as ig]
   [telegrambot-lib.core :as tbot]
   [taoensso.timbre :as log]
   [clojure.string :as str]))

(defn make-file-row [files]
  (mapv (fn [{:keys [id name]}]
          [{:text name :callback_data (str "/read " id)}])
        files))

(defn make-pagination-row [page total-pages]
  (cond
    (and (= page 1) (= total-pages 1)) []
    (= page 1) [[{:text "▶" :callback_data (str "/page " (inc page))}]]
    (= page total-pages) [[{:text "◀" :callback_data (str "/page " (dec page))}]]
    :else [[{:text "◀" :callback_data (str "/page " (dec page))}
            {:text "▶" :callback_data (str "/page " (inc page))}]]))

(defn make-keyboard [add-files-actions? files page total-pages]
  {:inline_keyboard (vec (concat (make-file-row files)
                                 (make-pagination-row page total-pages)
                                 (when add-files-actions?
                                   [[{:text "+" :callback_data "/upload"}]])))})

(defmethod ig/init-key ::check-delete-clear-msg-id [_ bot]
  (fn [user-id->msg-id user-id->clear-msg-id]
    (fn [user-id]
      (let [msg-id (user-id->msg-id user-id)]
        (log/info "Delete message. user-id: " user-id "; msg-id: " msg-id)
        (when msg-id
          (log/info "delete message: " (tbot/delete-message bot user-id msg-id))
          (user-id->clear-msg-id user-id))))))

(defmethod ig/init-key ::user-id->delete-keyboard-msg-id! [_ {:keys [user-id->clear-keyboard-msg-id!
                                                                    user-id->keyboard-msg-id
                                                                    check-delete-clear-msg-id]}]
  (check-delete-clear-msg-id user-id->keyboard-msg-id user-id->clear-keyboard-msg-id!))

(defmethod ig/init-key ::user-id->delete-video-msg-id! [_ {:keys [user-id->clear-video-msg-id!
                                                                 user-id->video-msg-id
                                                                 check-delete-clear-msg-id]}]
  (check-delete-clear-msg-id user-id->video-msg-id user-id->clear-video-msg-id!))

(defmethod ig/init-key ::main-keyboard
  [_ {:keys [send-message!
             list-files
             total-pages
             user-id->page
             user-id->delete-keyboard-msg-id!
             set-user-keyboard-msg-id!]}]
  (fn main-keyboard
    ([upd] (main-keyboard upd "Bыберите видео:"))
    ([upd msg]
     (log/info "in :main-keyboard")
     (let [user-id  (-> upd :user :id)
           pages (total-pages)
           page (min pages (user-id->page user-id))]
       (user-id->delete-keyboard-msg-id! user-id)
       (->> pages
            (make-keyboard (= :admin (get-in upd [:user :role])) (list-files page) page)
            (assoc {} :reply_markup)
            (send-message! user-id msg)
            :result
            :message_id
            (set-user-keyboard-msg-id! user-id))))))

(defmethod ig/init-key ::new-keyboard [_ {:keys [user-id->delete-video-msg-id! main-keyboard]}]
  (fn [upd]
    (log/info "in :new-keyboard")
    (-> upd :user :id user-id->delete-video-msg-id!)
    (main-keyboard upd)))

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
      (tbot/edit-message-reply-markup bot user-id msg-id keyboard)
      (user-id+page->update-page! user-id page))))

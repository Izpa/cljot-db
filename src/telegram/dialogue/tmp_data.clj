(ns telegram.dialogue.tmp-data
  (:require
   [integrant.core :as ig]
   [taoensso.timbre :as log]
   [utils :refer [pformat]]))

(defmethod ig/init-key ::user-data [_ _]
  (atom {}))

(defmethod ig/init-key ::user-id->state [_ user-data]
  (fn [user-id]
    (get-in @user-data [user-id :state] :default)))

(defmethod ig/init-key ::set-user-state! [_ user-states]
  (fn [user-id state]
    (swap! user-states assoc-in [user-id :state] state)))

(defmethod ig/init-key ::user-id->keyboard-msg-id [_ user-data]
  (fn [user-id]
    (log/info "in user-id->keyboard-msg-id")
    (log/info (pformat @user-data))
    (get-in @user-data [user-id :keyboard-msg-id])))

(defmethod ig/init-key ::set-user-keyboard-msg-id! [_ {:keys [user-data add-user-tmp-msg-ids!]}]
  (fn [user-id keyboard-msg-id]
    (log/info "in set-user-keyboard-msg-id")
    (log/info (pformat @user-data))
    (add-user-tmp-msg-ids! user-id keyboard-msg-id)
    (swap! user-data assoc-in [user-id :keyboard-msg-id] keyboard-msg-id)))

(defmethod ig/init-key ::user-id->clear-keyboard-msg-id! [_ user-data]
  (fn [user-id]
    (log/info "in clear-user-keyboard-msg-id")
    (log/info (pformat @user-data))
    (swap! user-data update user-id dissoc :keyboard-msg-id)))

(defmethod ig/init-key ::user-id->tmp-msg-ids [_ user-data]
  (fn [user-id]
    (get-in @user-data [user-id :tmp-msg-ids])))

(defmethod ig/init-key ::add-user-tmp-msg-ids! [_ user-data]
  (fn [user-id & tmp-msg-ids]
    (swap! user-data update-in [user-id :tmp-msg-ids] (fnil into #{}) tmp-msg-ids)))

(defmethod ig/init-key ::user-id->clear-tmp-msg-ids! [_ user-data]
  (fn [user-id]
    (swap! user-data update user-id dissoc :tmp-msg-ids)))

(defmethod ig/init-key ::user-id->page [_ user-data]
  (fn [user-id]
    (get-in @user-data [user-id :page] 1)))

(defmethod ig/init-key ::user-id+page->update-page! [_ user-data]
  (fn [user-id page]
    (swap! user-data assoc-in [user-id :page] page)))

(defmethod ig/init-key ::user-id->video-name [_ user-data]
  (fn [user-id]
    (get-in @user-data [user-id :video-name])))

(defmethod ig/init-key ::set-user-video-name! [_ user-data]
  (fn [user-id video-name]
    (swap! user-data assoc-in [user-id :video-name] video-name)))

(defmethod ig/init-key ::user-id->clear-video-name! [_ user-data]
  (fn [user-id]
    (swap! user-data update user-id dissoc :video-name)))

(defmethod ig/init-key ::set-user-rename-file-id! [_ user-data]
  (fn [user-id file-id]
    (swap! user-data assoc-in [user-id :rename-file-id] file-id)))

(defmethod ig/init-key ::user-id->rename-file-id [_ user-data]
  (fn [user-id]
    (get-in @user-data [user-id  :rename-file-id])))

(defmethod ig/init-key ::user-id->clear-rename-file-id! [_ user-data]
  (fn [user-id]
    (swap! user-data update user-id dissoc :rename-file-id)))

(defmethod ig/init-key ::user-id->clear-critical-tmp-data [_ clear-fns]
  (fn [user-id]
    (doseq [f clear-fns]
      (f user-id))))

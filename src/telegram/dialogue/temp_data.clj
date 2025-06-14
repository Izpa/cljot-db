(ns telegram.dialogue.temp-data
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
    (get-in @user-data [user-id :keyboard-msg-id])))

(defmethod ig/init-key ::set-user-keyboard-msg-id! [_ user-data]
  (fn [user-id keyboard-msg-id]
    (swap! user-data assoc-in [user-id :keyboard-msg-id] keyboard-msg-id)))

(defmethod ig/init-key ::user-id->clear-keyboard-msg-id! [_ user-data]
  (fn [user-id]
    (swap! user-data update user-id dissoc :keyboard-msg-id)))

(defmethod ig/init-key ::user-id->video-msg-id [_ user-data]
  (fn [user-id]
    (get-in @user-data [user-id :video-msg-id])))

(defmethod ig/init-key ::set-user-video-msg-id! [_ user-data]
  (fn [user-id video-msg-id]
    (swap! user-data assoc-in [user-id :video-msg-id] video-msg-id)))

(defmethod ig/init-key ::user-id->clear-video-msg-id! [_ user-data]
  (fn [user-id]
    (swap! user-data update user-id dissoc :video-msg-id)))

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

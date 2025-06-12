(ns telegram.dialogue.user
  (:require
   [integrant.core :as ig]
   [clojure.string :as str]))

(defmethod ig/init-key ::states [_ _]
  (atom {}))

(defmethod ig/init-key ::admin? [_ {:keys [admin-chat-ids]}]
  #(contains? admin-chat-ids %))

(defmethod ig/init-key ::check [_ {:keys [select-user check-invite insert-user!]}]
  (fn [user-id text chat answer]
    (cond
      (select-user user-id) :known
      (check-invite text) (do (insert-user! chat)
                              (answer "Добро пожаловать!")
                              :new)
      :else nil)))

(defmethod ig/init-key ::check-invite [_ {:keys [invite]}]
  (fn [msg-text]
    (when invite
      (let [[command received-invite & args] (str/split msg-text #"\\s+")]
        (and (= command "/start")
             (= invite received-invite)
             (empty? args))))))


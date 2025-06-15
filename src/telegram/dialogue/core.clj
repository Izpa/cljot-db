(ns telegram.dialogue.core
  (:require
   [integrant.core :as ig]
   [taoensso.timbre :as log]
   [utils :refer [pformat]]))

(defmethod ig/init-key ::user-id->role [_ {:keys [admin-chat-ids select-user]}]
  (fn [user-id]
    (cond
      (contains? admin-chat-ids user-id) :admin
      (select-user user-id) :user
      :else :anonymous)))

(defmethod ig/init-key ::start [_ {:keys [invite insert-user! new-keyboard]}]
  (fn [upd]
    (when (= invite
             (-> upd :val :args first))
      (insert-user! (:user upd))
      (new-keyboard upd "Добро пожаловать! Выберите видео:"))))

(defmethod ig/init-key ::fsm [_ {:keys [user-id->role
                                        user-id->state
                                        set-user-state!
                                        config
                                        send-message!
                                        default-state]}]
  (fn [upd]
    (log/info "Normalized UPD: " (pformat upd))
    (let [user-id (-> upd :user :id)
          role (user-id->role user-id)
          upd-type (:type upd)
          state-name (user-id->state user-id)
          state (or (get-in config [state-name upd-type])
                    (get-in config [state-name :default]))
          {:keys [handler
                  next
                  roles]} (or (if (= :command upd-type)
                                (->> upd :val :command (get state))
                                state)
                              default-state)]
      (log/info "State: " state-name
                "(nil?: " (nil? state)
                "); Next: " next
                "; Roles: " roles)
      (if (or (some #{role} roles)
              (= role :admin))
        (do
          (handler (assoc-in upd [:user :role] role))
          (set-user-state! user-id (or next :default)))
        (send-message! user-id
                       (case role
                         :user  "Данное действие доступно только администраторам"
                         :anonymous "Это приватный бот, доступ только по инвайтам"))))))

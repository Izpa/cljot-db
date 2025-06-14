(ns telegram.dialogue.core
  (:require
   [integrant.core :as ig]
   [taoensso.timbre :as log]))

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
                                        send-message!]}]
  (fn [upd]
    (let [user-id (-> upd :user :id)
          role (user-id->role user-id)
          upd-type (:type upd)
          state-name (user-id->state user-id)
          state (get-in [state-name upd-type] config)
          {:keys [handler
                  next
                  roles]} (if (= :command upd-type)
                            (->> upd :val :command (get state))
                            state)]
      (log/info "State: " state-name "; Next: " next "; Roles: " roles)
      (if (some #{role} roles)
        (do
          (handler (assoc-in upd [:user :role] role))
          (set-user-state! next))
        (send-message! user-id
                       (case role
                         :admin "Неверно настроены права доступа для данного действия"
                         :user  "Данное действие доступно только администраторам"
                         :anonymous "Это приватный бот, доступ только по инвайтам"))))))

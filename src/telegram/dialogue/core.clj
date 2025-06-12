(ns telegram.dialogue.core
  (:require
   [integrant.core :as ig]
   [taoensso.timbre :as log]
   [utils :refer [pformat]]
   [telegram.dialogue.msg-helpers :as msg-helpers]))

(defmethod ig/init-key ::dispatch [_ {:keys [fsm user-states]}]
  (fn [msg answer {:keys [is-admin? is-new-user?]}]
    (let [uid        (msg-helpers/user-id msg)
          {:keys [state-name]} (get @user-states uid {:state-name :default})
          state-def  (get fsm state-name)
          event      (msg-helpers/classify-event msg)
          trigger    (msg-helpers/extract-trigger msg)
          transition (get-in state-def [event trigger]
                             (get-in state-def [event]))] ;; fallback on generic event
      (log/infof "FSM State=%s Event=%s Trigger=%s" state-name event trigger)
      (log/infof "FSM Transition: %s" (keys transition))
      (if-let [do-fn (:do transition)]
        (do
          (do-fn {:answer answer
                  :msg msg
                  :user-id uid
                  :admin? is-admin?
                  :new-user? is-new-user?})
          (log/infof "FSM: new state for user %s → %s" uid (:next transition))
          (swap! user-states update uid
                 (fn [state]
                   (-> state
                       (assoc :state-name (:next transition))))))
        (do
          (log/warnf "FSM: no valid transition for state=%s event=%s trigger=%s"
                     state-name event trigger)
          (answer msg-helpers/default-error-message))))))


(defmethod ig/init-key ::process-msg [_ {:keys [send-message dispatch admin? check-user]}]
  (fn [msg]
  (let [uid      (msg-helpers/user-id msg)
        text     (:text msg)
        answer   (partial send-message uid)
        is-admin (admin? uid)
        result   (when-not is-admin
                   (check-user uid text (:chat msg) answer))]
    (log/info "is-admin? " is-admin)
    (log/info "check-user " result)
    (cond
      (nil? uid) (log/warn "Strange message without chat-id: " (pformat msg))
      (or is-admin result)
      (dispatch msg answer {:is-admin? is-admin
                            :is-new-user? (= result :new)})
      :else (answer "This is a private bot. Access denied.")))))

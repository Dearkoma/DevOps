import React from 'react'

export default function Layout({ pages, currentPage, onNavigate, children }) {
  return (
    <div className="app">
      <aside className="sidebar">
        <div className="sidebar-logo">
          🚀 DevOps<span>Platform</span>
        </div>
        <nav className="sidebar-nav">
          {Object.entries(pages).map(([key, { label, icon }]) => (
            <a
              key={key}
              className={currentPage === key ? 'active' : ''}
              onClick={() => onNavigate(key)}
            >
              <span className="icon">{icon}</span>
              {label}
            </a>
          ))}
        </nav>
      </aside>
      <main className="main">{children}</main>
    </div>
  )
}
